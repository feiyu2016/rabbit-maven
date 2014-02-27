package rabbit.proxy;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.logging.Level;
import java.util.logging.Logger;
import rabbit.http.HttpHeader;
import rabbit.httpio.HttpHeaderSender;
import rabbit.httpio.HttpHeaderSentListener;
import rabbit.httpio.HttpResponseListener;
import rabbit.httpio.HttpResponseReader;
import rabbit.io.BufferHandle;
import rabbit.io.CacheBufferHandle;
import rabbit.io.ProxyChain;
import rabbit.io.Resolver;
import rabbit.io.WebConnection;
import rabbit.io.WebConnectionListener;
import rabbit.util.Base64;
import rabbit.rnio.impl.Closer;

/** A handler that shuttles ssl traffic
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class SSLHandler implements TunnelDoneListener {
    private final HttpProxy proxy;
    private final Connection con;
    private final HttpHeader request;
    private final TrafficLoggerHandler tlh;
    private final Resolver resolver;
    private SocketChannel channel;
    private BufferHandle bh;
    private BufferHandle sbh;
    private WebConnection wc;
    private static final Logger logger = Logger.getLogger (SSLHandler.class.getName ());

    /** Create a new SSLHandler
     * @param proxy the HttpProxy this SSL connection is serving
     * @param con the Connection to handle
     * @param request the CONNECT header
     * @param tlh the traffic statistics gatherer
     */
    public SSLHandler (final HttpProxy proxy, final Connection con,
                       final HttpHeader request, final TrafficLoggerHandler tlh) {
        this.proxy = proxy;
        this.con = con;
        this.request = request;
        this.tlh = tlh;
        final ProxyChain pc = con.getProxy ().getProxyChain ();
        resolver = pc.getResolver (request.getRequestURI ());
    }

    /** Are we allowed to proxy ssl-type connections ?
     * @return true if we allow the CONNECT &lt;port&gt; command.
     */
    public boolean isAllowed () {
        final String hp = request.getRequestURI ();
        final int c = hp.indexOf (':');
        Integer port = 443;
        if (c >= 0) {
            try {
                port = Integer.valueOf(hp.substring (c + 1));
            } catch (NumberFormatException e) {
                logger.warning ("Connect to odd port: " + e);
                return false;
            }
        }
        if (!proxy.proxySSL) {
            return false;
        }
        if (proxy.proxySSL && proxy.sslports == null) {
            return true;
        }
        for (int i = 0; i < proxy.sslports.size (); i++) {
            if (port.equals (proxy.sslports.get (i))) {
                return true;
            }
        }
        return false;
    }

    /** handle the tunnel.
     * @param channel the client channel
     * @param bh the buffer handle used, may contain data from client.
     */
    public void handle (final SocketChannel channel, final BufferHandle bh) {
        this.channel = channel;
        this.bh = bh;
        if (resolver.isProxyConnected ()) {
            final String auth = resolver.getProxyAuthString ();
            // it should look like this (using RabbIT:RabbIT):
            // Proxy-authorization: Basic UmFiYklUOlJhYmJJVA==
            if (auth != null && !auth.equals ("")) {
                request.setHeader("Proxy-authorization",
                                  "Basic " + Base64.encode(auth));
            }
        }
        final WebConnectionListener wcl = new WebConnector ();
        proxy.getWebConnection (request, wcl);
    }

    private class WebConnector implements WebConnectionListener {
        private final String uri;

        public WebConnector () {
            uri = request.getRequestURI ();
            // java needs protocoll to build URL
            request.setRequestURI ("http://" + uri);
        }

        @Override
        public void connectionEstablished (final WebConnection wce) {
            wc = wce;
            if (resolver.isProxyConnected ()) {
                request.setRequestURI (uri); // send correct connect to next proxy.
                setupChain ();
            } else {
                final BufferHandle bh = new CacheBufferHandle (con.getBufferHandler ());
                sendOkReplyAndTunnel (bh);
            }
        }

        @Override
        public void timeout () {
            final String err =
                    "SSLHandler: Timeout waiting for web connection: " + uri;
            logger.warning (err);
            closeDown ();
        }

        @Override
        public void failed (final Exception e) {
            warn ("SSLHandler: failed to get web connection to: " + uri, e);
            closeDown ();
        }
    }

    private void closeDown () {
        if (bh != null) {
            bh.possiblyFlush();
        }
        if (sbh != null) {
            sbh.possiblyFlush();
        }
        Closer.close (wc, logger);
        wc = null;
        con.logAndClose (null);
    }

    private void warn (final String err, final Exception e) {
        logger.log (Level.WARNING, err, e);
    }

    private void setupChain () {
        final HttpResponseListener cr = new ChainResponseHandler ();
        try {
            final HttpResponseReader hrr =
                    new HttpResponseReader (wc.getChannel (),
                                            proxy.getNioHandler (),
                                            tlh.getNetwork (),
                                            con.getBufferHandler (), request,
                                            proxy.getStrictHttp (),
                                            resolver.isProxyConnected (), cr);
            hrr.sendRequestAndWaitForResponse ();
        } catch (IOException e) {
            warn ("IOException when waiting for chained response: " +
                  request.getRequestURI (), e);
            closeDown ();
        }
    }

    private class ChainResponseHandler implements HttpResponseListener {
        @Override
        public void httpResponse (final HttpHeader response, final BufferHandle rbh,
                                  final boolean keepalive, final boolean isChunked,
                                  final long dataSize) {
            final String status = response.getStatusCode ();
            if (!"200".equals (status)) {
                closeDown ();
            } else {
                sendOkReplyAndTunnel (rbh);
            }
        }

        @Override
        public void failed (final Exception cause) {
            warn ("SSLHandler: failed to get chained response: " +
                  request.getRequestURI (), cause);
            closeDown ();
        }

        @Override
        public void timeout () {
            final String err = "SSLHandler: Timeout waiting for chained response: " +
                               request.getRequestURI ();
            logger.warning (err);
            closeDown ();
        }
    }

    private void sendOkReplyAndTunnel (final BufferHandle server2client) {
        final HttpHeader reply = new HttpHeader ();
        reply.setStatusLine ("HTTP/1.0 200 Connection established");
        reply.setHeader ("Proxy-agent", proxy.getServerIdentity ());

        final HttpHeaderSentListener tc = new TunnelConnected (server2client);
        try {
            final HttpHeaderSender hhs =
                    new HttpHeaderSender (channel, proxy.getNioHandler (),
                                          tlh.getClient (), reply, false, tc);
            hhs.sendHeader ();
        } catch (IOException e) {
            warn ("IOException when sending header", e);
            closeDown ();
        }
    }

    private class TunnelConnected implements HttpHeaderSentListener {
        private final BufferHandle server2client;

        public TunnelConnected (final BufferHandle server2client) {
            this.server2client = server2client;
        }

        @Override
        public void httpHeaderSent () {
            tunnelData (server2client);
        }

        @Override
        public void timeout () {
            logger.warning ("SSLHandler: Timeout when sending http header: " +
                            request.getRequestURI ());
            closeDown ();
        }

        @Override
        public void failed (final Exception e) {
            warn ("SSLHandler: Exception when sending http header: " +
                  request.getRequestURI (), e);
            closeDown ();
        }
    }

    private void tunnelData (final BufferHandle server2client) {
        sbh = server2client;
        final SocketChannel sc = wc.getChannel ();
        final Tunnel tunnel =
                new Tunnel (proxy.getNioHandler (), channel, bh,
                            tlh.getClient (), sc, server2client,
                            tlh.getNetwork (), this);
        tunnel.start ();
    }

    @Override
    public void tunnelClosed () {
        if (wc != null) {
            con.logAndClose (null);
            Closer.close (wc, logger);
        }
        wc = null;
    }
}
