package rabbit.proxy;

import lombok.extern.slf4j.Slf4j;

import java.util.Locale;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import rabbit.rnio.BufferHandler;
import rabbit.rnio.NioHandler;
import rabbit.rnio.StatisticsHolder;
import rabbit.rnio.impl.Acceptor;
import rabbit.rnio.impl.AcceptorListener;
import rabbit.rnio.impl.BasicStatisticsHolder;
import rabbit.rnio.impl.CachingBufferHandler;
import rabbit.rnio.impl.MultiSelectorNioHandler;
import rabbit.rnio.impl.SimpleThreadFactory;
import rabbit.http.HttpDateParser;
import rabbit.http.HttpHeader;
import rabbit.httpio.ProxiedProxyChain;
import rabbit.httpio.SimpleProxyChain;
import rabbit.io.ConnectionHandler;
import rabbit.io.ProxyChain;
import rabbit.io.ProxyChainFactory;
import rabbit.io.WebConnection;
import rabbit.io.WebConnectionListener;
import rabbit.util.Config;
import rabbit.util.Counter;
import rabbit.util.SProperties;

/** A filtering and caching http proxy.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
@Slf4j
public class HttpProxy {

    /** Current version */
    private static final String VERSION = "RabbIT proxy version 5.0";

    /** The current config of this proxy. */
    private Config config;

    /** The time this proxy was started. Time in millis. */
    private long started;

    /** The identity of this server. */
    private String serverIdentity = VERSION;

    /** The id sequence for acceptors. */
    private static int acceptorId = 0;

    /** The http header filterer. */
    private HttpHeaderFilterer httpHeaderFilterer;

    /** The connection handler */
    private ConnectionHandler conhandler;

    /** The local adress of the proxy. */
    private InetAddress localhost;

    /** The port the proxy is using. */
    private int port = -1;

    /** The proxy chain we are using */
    private ProxyChain proxyChain;

    /** The serversocket the proxy is using. */
    private ServerSocketChannel ssc = null;

    private NioHandler nioHandler;

    /** The buffer handlers. */
    private final BufferHandler bufferHandler = new CachingBufferHandler();

    /** If this proxy is using strict http parsing. */
    private boolean strictHttp = true;

    /** The counter of events. */
    private final Counter counter = new Counter();

    /** Are we allowed to proxy ssl? */
    boolean proxySSL = false;
    /** The List of acceptable ssl-ports. */
    List<Integer> sslports = null;

    /** All the currently active connections. */
    private final List<Connection> connections = new ArrayList<>();

    /** The total traffic in and out of this proxy. */
    private final TrafficLoggerHandler tlh = new TrafficLoggerHandler();

    /** The factory for http header generator */
    private HttpGeneratorFactory hgf;

    /** Create a new HttpProxy.
     * @throws UnknownHostException if the local host address can not
     *         be determined
     */
    public HttpProxy() throws UnknownHostException {
        localhost = InetAddress.getLocalHost();
    }

    private void setupDateParsing() {
        final TimeZone tz = new SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss 'GMT'", Locale.US).getTimeZone();
        final GregorianCalendar gc = new GregorianCalendar();
        gc.setTime(new Date());
        final long offset = tz.getOffset(gc.get(Calendar.ERA),
                                         gc.get(Calendar.YEAR),
                                         gc.get(Calendar.MONTH),
                                         gc.get(Calendar.DAY_OF_MONTH),
                                         gc.get(Calendar.DAY_OF_WEEK),
                                         gc.get(Calendar.MILLISECOND));

        HttpDateParser.setOffset(offset);
    }

    private void setupNioHandler() {
        final String section = getClass().getName();
        final int cpus = Runtime.getRuntime().availableProcessors();
        final int threads = getInt(section, "num_selector_threads", cpus);
        final ExecutorService es = Executors.newCachedThreadPool();
        final StatisticsHolder sh = new BasicStatisticsHolder();
        final Long timeout = (long) 15000;
        try {
            nioHandler =
                    new MultiSelectorNioHandler(es, sh, threads, timeout);
        } catch (IOException e) {
            log.error("Failed to create the NioHandler", e);
            stop();
        }
    }

    private ProxyChain setupProxyChainFromFactory(final String pcf) {
        try {
            final Class<? extends ProxyChainFactory> clz =
                    load3rdPartyClass(pcf, ProxyChainFactory.class);
            final ProxyChainFactory factory = clz.newInstance();
            final SProperties props = config.getProperties(pcf);
            return factory.getProxyChain(props, nioHandler);
        } catch (Exception e) {
            log.warn("Unable to create the proxy chain will fall back to the default one.", e);
        }
        return null;
    }

    /* TODO: remove this method, only kept for backwards compability. */
    private ProxyChain setupProxiedProxyChain(final String pname, final String pport,
                                              final String pauth) {
        try {
            final InetAddress proxy = InetAddress.getByName(pname);
            try {
                final int port = Integer.parseInt(pport);
                return new ProxiedProxyChain(proxy, port, pauth);
            } catch (NumberFormatException e) {
                log.error("Strange proxyport: '{}', will not chain", pport);
            }
        } catch (UnknownHostException e) {
            log.error("Unknown proxyhost: '{}', will not chain", pname);
        }
        return null;
    }

    /** Configure the chained proxy rabbit is using (if any).
     */
    private void setupProxyConnection() {
        final String sec = getClass().getName();
        final String pcf =
                config.getProperty(sec, "proxy_chain_factory", "").trim();
        final String pname = config.getProperty(sec, "proxyhost", "").trim();
        final String pport = config.getProperty(sec, "proxyport", "").trim();
        final String pauth = config.getProperty(sec, "proxyauth");

        if (!"".equals(pcf)) {
            proxyChain = setupProxyChainFromFactory(pcf);
        } else if (!pname.equals("") && !pport.equals("")) {
            proxyChain = setupProxiedProxyChain(pname, pport, pauth);
        }
        if (proxyChain == null) {
            proxyChain = new SimpleProxyChain(nioHandler);
        }
    }

    /** Configure the SSL support RabbIT should have.
     */
    private void setupSSLSupport() {
        String ssl = config.getProperty(getClass().getName(), "proxySSL", "no");
        ssl = ssl.trim();
        switch(ssl) {
            case "no":
                proxySSL = false;
                break;
            case "yes":
                proxySSL = true;
                sslports = null;
                break;
            default:
                proxySSL = true;
                // ok, try to get the portnumbers.
                sslports = new ArrayList<>();
                final StringTokenizer st = new StringTokenizer(ssl, ",");
                while (st.hasMoreTokens()) {
                    String s = null;
                    try {
                        final Integer port = Integer.valueOf(s = st.nextToken());
                        sslports.add(port);
                    } catch (NumberFormatException e) {
                        log.warn("bad number: '{}' for ssl port, ignoring.", s);
                    }
                }
                break;
        }
    }

    /** Toogle the strict http flag.
     * @param b the new mode for the strict http flag
     */
    public void setStrictHttp(final boolean b) {
        this.strictHttp = b;
    }

    /** Check if strict http is turned on or off.
     * @return the strict http flag
     */
    public boolean getStrictHttp() {
        return strictHttp;
    }

    private void setupConnectionHandler() {
        if (nioHandler == null) {
            log.info("nioHandler == null");
            return;
        }
        conhandler = new ConnectionHandler(counter, proxyChain, nioHandler);
        final String section = conhandler.getClass().getName();
        conhandler.setup(config.getProperties(section));
    }

    private void setupHttpGeneratorFactory() {
        final String def = StandardHttpGeneratorFactory.class.getName();
        final String hgfClass = config.getProperty(getClass().getName(),
                                                   "http_generator_factory", def);
        try {
            final Class<? extends HttpGeneratorFactory> clz =
                    load3rdPartyClass(hgfClass, HttpGeneratorFactory.class);
            hgf = clz.newInstance();
        } catch (Exception e) {
            log.warn("Unable to create the http generator factory, will fall back to the default one.", e);
            hgf = new StandardHttpGeneratorFactory();
        }
        final String section = hgf.getClass().getName();
        hgf.setup(config.getProperties(section));
    }

    public void setConfig(final Config config) {
        this.config = config;
        setupDateParsing();
        setupNioHandler();
        setupProxyConnection();
        final String cn = getClass().getName();
        serverIdentity = config.getProperty(cn, "serverIdentity", VERSION);
        setStrictHttp(false);
        setupSSLSupport();
        loadClasses();
        openSocket();
        setupConnectionHandler();
        setupHttpGeneratorFactory();
        log.info("{}: Configuration loaded: ready for action.", serverIdentity);
    }

    private int getInt(final String section, final String key, final int defaultValue) {
        final String defVal = Integer.toString(defaultValue);
        final String configValue = config.getProperty(section, key, defVal).trim();
        return Integer.parseInt(configValue);
    }

    /** Open a socket on the specified port
     *  also make the proxy continue accepting connections.
     */
    private void openSocket() {
        final String section = getClass().getName();
        final int tport = getInt(section, "port", 9666);

        final String bindIP = config.getProperty(section, "listen_ip");
        if (tport != port) {
            try {
                closeSocket();
                port = tport;
                ssc = ServerSocketChannel.open();
                ssc.configureBlocking(false);
                if (bindIP == null) {
                    ssc.socket().bind(new InetSocketAddress(port));
                } else {
                    final InetAddress ia = InetAddress.getByName(bindIP);
                    log.info("listening on inetaddress: {}:{} on inetAddress: {}", ia, port, ia);
                    ssc.socket().bind(new InetSocketAddress(ia, port));
                }
                final AcceptorListener listener =
                        new ProxyConnectionAcceptor(acceptorId++, this);
                final Acceptor acceptor = new Acceptor(ssc, nioHandler, listener);
                acceptor.register();
            } catch (IOException e) {
                log.error("Failed to open serversocket on port {}", port, e);
                stop();
            }
        }
    }

    /** Closes the serversocket and makes the proxy stop listening for
     *    connections.
     */
    private void closeSocket() {
        try {
            port = -1;
            if (ssc != null) {
                ssc.close();
                ssc = null;
            }
        } catch (IOException e) {
            log.error("Failed to close serversocket on port {}", port);
            stop();
        }
    }

    private void closeNioHandler() {
        if (nioHandler != null) {
            nioHandler.shutdown();
        }
    }

    /** Make sure all filters and handlers are available
     */
    private void loadClasses() {
        final String contentFilters = config.getProperty("Filters", "contentfilters", "");
        String comma = "";
        if (contentFilters.length() > 0) {
            comma = ",";
        }

        final String in = contentFilters + comma + rabbit.filter.HttpBaseFilter.class.getName();
        final String out = rabbit.filter.HttpBaseFilter.class.getName();
        httpHeaderFilterer = new HttpHeaderFilterer(in, out, contentFilters, config, this);
    }


    /** Run the proxy in a separate thread. */
    public void start() {
        started = System.currentTimeMillis();
        nioHandler.start(new SimpleThreadFactory());
    }

    /** Run the proxy in a separate thread. */
    public void stop() {
        log.info("HttpProxy.stop() called, shutting down");
        synchronized (this) {
            closeSocket();
            // TODO: wait for remaining connections.
            // TODO: as it is now, it will just close connections in the middle.
            closeNioHandler();
        }
    }

    /** Get the NioHandler that this proxy is using.
     * @return the NioHandler in use 
     */
    public NioHandler getNioHandler() {
        return nioHandler;
    }

    /** Get the time this proxy was started.
     * @return the start time as returned from System.currentTimeMillis()
     */
    public long getStartTime() {
        return started;
    }

    ServerSocketChannel getServerSocketChannel() {
        return ssc;
    }

    /** Get the current Counter
     * @return the Ćounter in use
     */
    public Counter getCounter() {
        return counter;
    }

    HttpHeaderFilterer getHttpHeaderFilterer() {
        return httpHeaderFilterer;
    }

    /** Get the configuration of the proxy.
     * @return the current configuration
     */
    public Config getConfig() {
        return config;
    }

    /** Get the current server identity.
     * @return the current identity
     */
    public String getServerIdentity() {
        return serverIdentity;
    }

    /** Get the local host.
     * @return the InetAddress of the host the proxy is running on.
     */
    public InetAddress getHost() {
        return localhost;
    }

    /** Get the port this proxy is using.
     * @return the port number the proxy is listening on.
     */
    public int getPort() {
        return port;
    }

    /** Get the ProxyChain this proxy is currently using
     * @return the current ProxyChain
     */
    public ProxyChain getProxyChain() {
        return proxyChain;
    }

    /** Try hard to check if the given address matches the proxy.
     *  Will use the localhost name and all ip addresses.
     * @param uhost the host name to check
     * @param urlport the port number to check
     * @return true if the given hostname and port matches this proxy
     */
    public boolean isSelf(final String uhost, final int urlport) {
        if (urlport == port) {
            final String proxyhost = localhost.getHostName();
            if (uhost.equalsIgnoreCase(proxyhost)) {
                return true;
            }
            try {
                final Enumeration<NetworkInterface> e =
                        NetworkInterface.getNetworkInterfaces();
                while (e.hasMoreElements()) {
                    final NetworkInterface ni = e.nextElement();
                    final Enumeration<InetAddress> ei = ni.getInetAddresses();
                    while (ei.hasMoreElements()) {
                        final InetAddress ia = ei.nextElement();
                        if (ia.getHostAddress().equalsIgnoreCase(uhost)) {
                            return true;
                        }
                        if (ia.isLoopbackAddress() &&
                            ia.getHostName().equalsIgnoreCase(uhost)) {
                            return true;
                        }
                    }
                }
            } catch (SocketException e) {
                log.warn("Failed to get network interfaces", e);
            }
        }
        return false;
    }

    /** Get a WebConnection.
     * @param header the http header to get the host and port from
     * @param wcl the listener that wants to get the connection.
     */
    public void getWebConnection(final HttpHeader header,
                                 final WebConnectionListener wcl) {
        conhandler.getConnection(header, wcl);
    }

    /** Release a WebConnection so that it may be reused if possible.
     * @param wc the WebConnection to release.
     */
    public void releaseWebConnection(final WebConnection wc) {
        conhandler.releaseConnection(wc);
    }

    /** Add a current connection
     * @param con the connection
     */
    public void addCurrentConnection(final Connection con) {
        synchronized (connections) {
            connections.add(con);
        }
    }

    /** Remove a current connection.
     * @param con the connection
     */
    public void removeCurrentConnection(final Connection con) {
        synchronized (connections) {
            connections.remove(con);
        }
    }

    /** Get the connection handler.
     * @return the current ConnectionHandler
     */
    public ConnectionHandler getConnectionHandler() {
        return conhandler;
    }

    /** Get all the current connections
     * @return all current connections
     */
    public List<Connection> getCurrentConnections() {
        synchronized (connections) {
            return Collections.unmodifiableList(connections);
        }
    }

    /** Update the currently transferred traffic statistics.
     * @param tlh the traffic statistics for some operation
     */
    protected void updateTrafficLog(final TrafficLoggerHandler tlh) {
        synchronized (this.tlh) {
            tlh.addTo(this.tlh);
        }
    }

    /** Get the currently transferred traffic statistics.
     * @return the current TrafficLoggerHandler
     */
    public TrafficLoggerHandler getTrafficLoggerHandler() {
        return tlh;
    }

    BufferHandler getBufferHandler() {
        return bufferHandler;
    }

    /** Get the current HttpGeneratorFactory.
     * @return the HttpGeneratorFactory in use
     */
    public HttpGeneratorFactory getHttpGeneratorFactory() {
        return hgf;
    }

    /** Load a 3:rd party class.
     * @param name the fully qualified name of the class to load
     * @param type the super type of the class
     * @param <T> the type of the clas
     * @return the loaded class
     * @throws ClassNotFoundException if the class can not be found
     */
    public <T> Class<? extends T> load3rdPartyClass(final String name,
                                                    final Class<T> type)
            throws ClassNotFoundException {
        return Class.forName(name).asSubclass(type);
    }
}
