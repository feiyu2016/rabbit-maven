package rabbit.io;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import rabbit.rnio.NioHandler;
import rabbit.rnio.ReadHandler;
import rabbit.http.HttpHeader;
import rabbit.util.Counter;
import rabbit.util.SProperties;

/** A class to handle the connections to the net.
 *  Tries to reuse connections whenever possible.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
@Slf4j
public class ConnectionHandler {
    // The counter to use.
    private final Counter counter;

    // The resolver to use
    private final ProxyChain proxyChain;

    // The available connections.
    private final Map<Address, List<WebConnection>> activeConnections;

    // The channels waiting for closing
    private final Map<WebConnection, CloseListener> wc2closer;

    // the keepalivetime.
    private long keepaliveTime = 1000;

    // the nio handler
    private final NioHandler nioHandler;

    // the socket binder
    private SocketBinder socketBinder = new DefaultBinder();

    /** Create a new ConnectionHandler.
     * @param counter the Counter to update with statistics
     * @param proxyChain the ProxyChain to use when doing dns lookups
     * @param nioHandler the NioHandler to use for network and background tasks
     */
    public ConnectionHandler(final Counter counter, final ProxyChain proxyChain,
                             final NioHandler nioHandler) {
        this.counter = counter;
        this.proxyChain = proxyChain;
        this.nioHandler = nioHandler;

        activeConnections = new HashMap<>();
        wc2closer = new ConcurrentHashMap<>();
    }

    /** Set the keep alive time for this handler.
     * @param milis the keep alive time in miliseconds.
     */
    private void setKeepaliveTime(final long milis) {
        keepaliveTime = milis;
    }

    /** Get the current keep alive time.
     * @return the keep alive time in miliseconds.
     */
    public long getKeepaliveTime() {
        return keepaliveTime;
    }

    /** Get a copy of the current connections.
     * @return the current connections
     */
    public Map<Address, List<WebConnection>> getActiveConnections() {
        final Map<Address, List<WebConnection>> ret =
                new HashMap<>();
        synchronized (activeConnections) {
            for (Map.Entry<Address, List<WebConnection>> me :
                    activeConnections.entrySet()) {
                ret.put(me.getKey(),
                        Collections.unmodifiableList(me.getValue()));
            }
        }
        return ret;
    }

    /** Get a WebConnection for the given header.
     * @param header the HttpHeader containing the URL to connect to.
     * @param wcl the Listener that wants the connection.
     */
    public void getConnection(final HttpHeader header,
                              final WebConnectionListener wcl) {
        // TODO: should we use the Host: header if its available? probably...
        final String requri = header.getRequestURI();
        URL url;
        try {
            url = new URL(requri);
        } catch (MalformedURLException e) {
            wcl.failed(e);
            return;
        }
        final Resolver resolver = proxyChain.getResolver(requri);
        final int port = url.getPort() > 0 ? url.getPort() : 80;
        final int rport = resolver.getConnectPort(port);

        resolver.getInetAddress(url, new InetAddressListener() {
            @Override
            public void lookupDone(final InetAddress ia) {
                final Address a = new Address(ia, rport);
                getConnection(header, wcl, a);
            }

            @Override
            public void unknownHost(final Exception e) {
                wcl.failed(e);
            }
        });
    }

    private void getConnection(final HttpHeader header,
                               final WebConnectionListener wcl,
                               final Address a) {
        WebConnection wc;
        counter.inc("WebConnections used");
        String method = header.getMethod();

        if (method != null) {
            // since we should not retry POST (and other) we
            // have to get a fresh connection for them..
            method = method.trim();
            if (!(method.equals("GET") || method.equals("HEAD"))) {
                wc = new WebConnection(a, socketBinder, counter);
            } else {
                wc = getPooledConnection(a, activeConnections);
                if (wc == null) {
                    wc = new WebConnection(a, socketBinder, counter);
                }
            }
            try {
                wc.connect(nioHandler, wcl);
            } catch (IOException e) {
                wcl.failed(e);
            }
        } else {
            final String err = "No method specified: " + header;
            wcl.failed(new IllegalArgumentException(err));
        }
    }

    private WebConnection
    getPooledConnection(final Address a, final Map<Address, List<WebConnection>> conns) {
        synchronized (conns) {
            final List<WebConnection> pool = conns.get(a);
            if (pool != null && pool.size() > 0) {
                final WebConnection wc = pool.remove(pool.size() - 1);
                if (pool.isEmpty()) {
                    conns.remove(a);
                }
                return unregister(wc);
            }
        }
        return null;
    }

    private WebConnection unregister(final WebConnection wc) {
        CloseListener closer;
        closer = wc2closer.remove(wc);
        if (closer != null) {
            nioHandler.cancel(wc.getChannel(), closer);
        }
        return wc;
    }

    private void removeFromPool(final WebConnection wc,
                                final Map<Address, List<WebConnection>> conns) {
        synchronized (conns) {
            final List<WebConnection> pool = conns.get(wc.getAddress());
            if (pool != null) {
                pool.remove(wc);
                if (pool.isEmpty()) {
                    conns.remove(wc.getAddress());
                }
            }
        }
    }

    /** Return a WebConnection to the pool so that it may be reused.
     * @param wc the WebConnection to return.
     */
    public void releaseConnection(final WebConnection wc) {
        counter.inc("WebConnections released");
        if (!wc.getChannel().isOpen()) {
            return;
        }

        final Address a = wc.getAddress();
        if (!wc.getKeepalive()) {
            closeWebConnection(wc);
            return;
        }

        synchronized (wc) {
            wc.setReleased();
        }
        synchronized (activeConnections) {
            List<WebConnection> pool = activeConnections.get(a);
            if (pool == null) {
                pool = new ArrayList<>();
                activeConnections.put(a, pool);
            } else {
                if (pool.contains(wc)) {
                    final String err =
                            "web connection already added to pool: " + wc;
                    throw new IllegalStateException(err);
                }
            }
            pool.add(wc);
            final CloseListener cl = new CloseListener(wc);
            wc2closer.put(wc, cl);
            cl.register();
        }
    }

    private void closeWebConnection(final WebConnection wc) {
        if (wc == null) {
            return;
        }
        if (!wc.getChannel().isOpen()) {
            return;
        }
        try {
            wc.close();
        } catch (IOException e) {
            log.warn("Failed to close WebConnection: {}", wc);
        }
    }

    private class CloseListener implements ReadHandler {
        private final WebConnection wc;
        private Long timeout;

        public CloseListener(final WebConnection wc) {
            this.wc = wc;
        }

        public void register() {
            timeout = nioHandler.getDefaultTimeout();
            nioHandler.waitForRead(wc.getChannel(), this);
        }

        @Override
        public void read() {
            closeChannel();
        }

        @Override
        public void closed() {
            closeChannel();
        }

        @Override
        public void timeout() {
            closeChannel();
        }

        @Override
        public Long getTimeout() {
            return timeout;
        }

        private void closeChannel() {
            try {
                wc2closer.remove(wc);
                removeFromPool(wc, activeConnections);
                wc.close();
            } catch (IOException e) {
                log.warn("CloseListener: Failed to close web connection: {}", e);
            }
        }

        @Override
        public boolean useSeparateThread() {
            return false;
        }

        @Override
        public String getDescription() {
            return "ConnectionHandler$CloseListener: address: " +
                   wc.getAddress();
        }

        @Override public String toString() {
            return getClass().getSimpleName() + "{wc: " + wc + "}@" +
                   Integer.toString(hashCode(), 16);
        }
    }

    /** Configure this ConnectionHandler using the given properties.
     * @param config the properties to read the configuration from
     */
    public void setup(final SProperties config) {
        if (config == null) {
            return;
        }
        final String kat = config.getProperty("keepalivetime", "1000");
        try {
            setKeepaliveTime(Long.parseLong(kat));
        } catch (NumberFormatException e) {
            log.warn("Bad number for ConnectionHandler keepalivetime: '{}'", kat);
        }
        final String bindIP = config.getProperty("bind_ip");
        if (bindIP != null) {
            try {
                final InetAddress ia = InetAddress.getByName(bindIP);
                if (ia != null) {
                    log.info("Will bind to: {} for outgoing traffic", ia);
                    socketBinder = new BoundBinder(ia);
                }
            } catch (IOException e) {
                log.error("Failed to find inet address for: {}", bindIP, e);
            }
        }
    }
}
