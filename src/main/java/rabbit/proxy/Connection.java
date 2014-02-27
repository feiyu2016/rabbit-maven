package rabbit.proxy;

import java.util.Locale;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.logging.Level;
import java.util.logging.Logger;

import rabbit.rnio.BufferHandler;
import rabbit.rnio.NioHandler;
import rabbit.rnio.TaskIdentifier;
import rabbit.rnio.impl.Closer;
import rabbit.rnio.impl.DefaultTaskIdentifier;
import rabbit.handler.BaseHandler;
import rabbit.handler.Handler;
import rabbit.handler.MultiPartHandler;
import rabbit.http.HttpHeader;
import rabbit.httpio.HttpHeaderListener;
import rabbit.httpio.HttpHeaderReader;
import rabbit.httpio.HttpHeaderSender;
import rabbit.httpio.HttpHeaderSentListener;
import rabbit.httpio.RequestLineTooLongException;
import rabbit.io.BufferHandle;
import rabbit.io.CacheBufferHandle;
import rabbit.io.ProxyChain;
import rabbit.io.Resolver;
import rabbit.util.Counter;

/** The base connection class for rabbit.
 *
 *  This is the class that handle the http protocoll for proxies.
 *
 *  For the technical overview of how connections and threads works
 *  see the file htdocs/technical_documentation/thread_handling_overview.txt
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class Connection {
    /** The id of this connection. */
    private final ConnectionId id;

    /** The client channel */
    private final SocketChannel channel;

    /** The current request */
    private HttpHeader request;

    /** The current request buffer handle */
    private BufferHandle requestHandle;

    /** The buffer handler. */
    private final BufferHandler bufHandler;

    /** The proxy we are serving */
    private final HttpProxy proxy;

    /** The current status of this connection. */
    private String status;

    /** The time this connection was started. */
    private long started;

    private boolean  keepalive      = true;
    private boolean  chunk          = true;

    /** If the user has authenticated himself */
    private String userName = null;
    private String password = null;

    /* Current status information */
    private String requestVersion = null;
    private String requestLine   = null;
    private String statusCode    = null;
    private String extraInfo     = null;
    private String contentLength = null;

    private ClientResourceHandler clientResourceHandler;

    private final HttpGenerator responseHandler;

    private final TrafficLoggerHandler tlh = new TrafficLoggerHandler();

    private static final Logger logger = Logger.getLogger(Connection.class.getName());

    /** Create a new Connection
     * @param id the ConnectionId of this connection.
     * @param channel the SocketChannel to the client.
     * @param proxy the HttpProxy that this connection belongs to.
     * @param bufHandler the BufferHandler to use for getting ByteBuffers.
     */
    public Connection(final ConnectionId id,    final SocketChannel channel,
                      final  HttpProxy proxy, final BufferHandler bufHandler) {
        this.id = id;
        this.channel = channel;
        this.proxy = proxy;
        this.requestHandle = new CacheBufferHandle(bufHandler);
        this.bufHandler = bufHandler;
        proxy.addCurrentConnection(this);
        final HttpGeneratorFactory hgf = proxy.getHttpGeneratorFactory();
        responseHandler = hgf.create(proxy.getServerIdentity(), this);
    }

    /**
     * @return the ConnectionId of this connection
     */
    public ConnectionId getId() {
        return id;
    }

    /** Read a request.
     */
    public void readRequest() {
        clearStatuses();
        try {
            final HttpHeaderListener clientListener = new RequestListener();
            final HttpHeaderReader hr =
                    new HttpHeaderReader(channel, requestHandle, getNioHandler(),
                                         tlh.getClient(), true,
                                         proxy.getStrictHttp(), clientListener);
            hr.readHeader();
        } catch (Throwable ex) {
            handleFailedRequestRead(ex);
        }
    }

    private boolean connectionReset(final Throwable t) {
        return t instanceof IOException && "Connection reset by peer".equals(t.getMessage());
    }

    private void handleFailedRequestRead(final Throwable t) {
        if (t instanceof RequestLineTooLongException) {
            final HttpHeader err = responseHandler.get414();
            // Send response and close
            sendAndClose(err);
        } else if (connectionReset(t)) {
            logger.log(Level.INFO, "Exception when reading request: " + t);
            closeDown();
        } else {
            logger.log(Level.INFO, "Exception when reading request", t);
            closeDown();
        }
    }

    private class RequestListener implements HttpHeaderListener {
        @Override
        public void httpHeaderRead(final HttpHeader header, final BufferHandle bh,
                                   final boolean keepalive, final boolean isChunked,
                                   final long dataSize) {
            setKeepalive(keepalive);
            requestRead(header, bh, isChunked, dataSize);
        }

        @Override
        public void closed() {
            closeDown();
        }

        @Override
        public void timeout() {
            closeDown();
        }

        @Override
        public void failed(final Exception e) {
            handleFailedRequestRead(e);
        }
    }

    private void handleInternalError(final Throwable t) {
        extraInfo =
                extraInfo != null ? extraInfo + t.toString() : t.toString();
        logger.log(Level.WARNING, "Internal Error", t);
        final HttpHeader internalError =
                responseHandler.get500(request.getRequestURI(), t);
        // Send response and close
        sendAndClose(internalError);
    }

    private void requestRead(final HttpHeader request, final BufferHandle bh,
                             final boolean isChunked, final long dataSize) {
        if (request == null) {
            logger.warning("Got a null request");
            closeDown();
            return;
        }
        status = "Request read, processing";
        this.request = request;
        this.requestHandle = bh;
        requestVersion = request.getHTTPVersion();
        if (request.isDot9Request()) {
            requestVersion = "HTTP/0.9";
        }
        requestVersion = requestVersion.toUpperCase(Locale.US);

        requestLine = request.getRequestLine();
        getCounter().inc("Requests");

        try {
            // SSL requests are special in a way...
            // Don't depend upon being able to build URLs from the header...
            if (request.isSSLRequest()) {
                checkAndHandleSSL(bh);
                return;
            }

            // Now set up handler of any posted data.
            // is the request resource chunked?
            if (isChunked) {
                setupChunkedContent();
            } else {
                // no? then try regular data
                final String ct = request.getHeader("Content-Type");
                if (hasRegularContent(request, ct, dataSize)) {
                    setupClientResourceHandler(dataSize);
                } else
                    // still no? then try multipart
                    if (ct != null) {
                        readMultiPart(ct);
                    }
            }

            final TaskIdentifier ti =
                    new DefaultTaskIdentifier(getClass().getSimpleName() +
                                              ".filterAndHandleRequest: ",
                                              request.getRequestURI());
            getNioHandler().runThreadTask(new Runnable() {
                @Override
                public void run() {
                    filterAndHandleRequest();
                }
            }, ti);
        } catch (Throwable t) {
            handleInternalError(t);
        }
    }

    private boolean hasRegularContent(final HttpHeader request, final String ct,
                                      final long dataSize) {
        return request.getContent() != null || !(ct != null && ct.startsWith("multipart/byteranges")) && dataSize > -1;
    }

    /** Filter the request and handle it.
     */
    private void filterAndHandleRequest() {
        // Filter the request based on the header.
        // A response means that the request is blocked.
        // For ad blocking, bad header configuration (http/1.1 correctness) ...
        final HttpHeaderFilterer filterer = proxy.getHttpHeaderFilterer();
        final HttpHeader badresponse = filterer.filterHttpIn(this, channel, request);
        if (badresponse != null) {
            statusCode = badresponse.getStatusCode();
            // Send response and close
            sendAndClose(badresponse);
        } else {
            handleRequest();
        }
    }

    /** Handle a request by getting the datastream (from the cache or the web).
     *  After getting the handler for the mimetype, send it.
     */
    private void handleRequest() {
        status = "Handling request";
        final RequestHandler rh = new RequestHandler(this);
        handleRequestBottom(rh);
    }

    private void handleRequestBottom(final RequestHandler rh) {
        if (rh.getContent() == null) {
            status = "Handling request - setting up web connection";
            // no usable cache entry so get the resource from the net.
            final ProxyChain pc = proxy.getProxyChain();
            final Resolver r = pc.getResolver(request.getRequestURI());
            final SWC swc =
                    new SWC(this, r, request, tlh, clientResourceHandler, rh);
            swc.establish();
        } else {
            resourceEstablished(rh);
        }
    }

    /** Fired when setting up a web connection failed.
     * @param rh the RequestHandler
     * @param cause the Exception that signaled the problem
     */
    public void webConnectionSetupFailed(final RequestHandler rh, final Exception cause) {
        if (cause instanceof UnknownHostException) {
            logger.warning(cause.toString() + ": " +
                           request.getRequestURI());
        } else {
            logger.warning("Failed to set up web connection to: " +
                           request.getRequestURI() + ", cause: " + cause);
        }
        doGateWayTimeout(cause);
    }

    /** Check if we must tunnel a request.
     *  Currently will only check if the Authorization starts with NTLM or Negotiate.
     * @return true if the current request needs to be handled by a tunnel
     */
    protected boolean mustTunnel() {
        final String auth = request.getHeader("Authorization");
        return auth != null &&
               (auth.startsWith("NTLM") || auth.startsWith("Negotiate"));
    }

    /** Fired when a web connection has been established.
     *  The web connection may be to the origin server or to an upstream proxy.
     * @param rh the RequestHandler for the current request 
     */
    public void webConnectionEstablished(final RequestHandler rh) {
        resourceEstablished(rh);
    }

    private void tunnel(final RequestHandler rh) {
        status = "Handling request - tunneling";
        final TunnelDoneListener tdl = new TDL(rh);
        final SocketChannel webChannel = rh.getWebConnection().getChannel();
        final Tunnel tunnel =
                new Tunnel(getNioHandler(), channel, requestHandle,
                           tlh.getClient(), webChannel,
                           rh.getWebHandle(), tlh.getNetwork(), tdl);
        tunnel.start();
    }

    private void resourceEstablished(final RequestHandler rh) {
        status = "Handling request - got resource";
        try {
            // and now we filter the response header if any.
            if (!request.isDot9Request()) {
                if (mustTunnel()) {
                    tunnel(rh);
                    return;
                }

                final String status = rh.getWebHeader().getStatusCode().trim();

                final HttpHeaderFilterer filterer =
                        proxy.getHttpHeaderFilterer();
                // Run output filters on the header
                final HttpHeader bad = filterer.filterHttpOut(this, channel, rh.getWebHeader());
                if (bad != null) {
                    // Bad cache entry or this request is blocked
                    rh.getContent().release();
                    // Send error response and close
                    sendAndClose(bad);
                    return;
                }

                if (status.length() > 0 && (status.equals("304") || status.equals("204") || status.charAt(0) == '1')) {
                    rh.getContent().release();
                    // Send success response and close
                    sendAndClose(rh.getWebHeader());
                    return;
                }
            }

            if (rh.getWebHeader() != null) {
                String ct = rh.getWebHeader().getHeader("Content-Type");
                if (ct != null) {
                    ct = ct.toLowerCase(Locale.US);
                    // remove some white spaces for easier configuration.
                    // "text/html; charset=iso-8859-1"
                    // "text/html;charset=iso-8859-1"
                    ct = ct.replace("; ", ";");
                    if (ct.startsWith("multipart/byteranges")) {
                        rh.setHandlerFactory(new MultiPartHandler());
                    }
                }
            }
            rh.setHandlerFactory(new BaseHandler());
            status = "Handling request - " +
                     rh.getHandlerFactory().getClass().getName();
            final Handler handler =
                    rh.getHandlerFactory().getNewInstance(this, tlh,
                                                          request,
                                                          rh.getWebHeader(),
                                                          rh.getContent(),
                                                          rh.getSize());
            if (handler == null) {
                doError(500, "Failed to find handler");
            } else {
                finalFixesOnWebHeader(rh, handler);
                // HTTP/0.9 does not support HEAD, so webheader should be valid.
                if (request.isHeadOnlyRequest()) {
                    rh.getContent().release();
                    sendAndRestart(rh.getWebHeader());
                } else {
                    handler.handle();
                }
            }
        } catch (Throwable t) {
            handleInternalError(t);
        }
    }

    private void finalFixesOnWebHeader(final RequestHandler rh, final Handler handler) {
        if (rh.getWebHeader() == null) {
            return;
        }
        if (chunk) {
            if (rh.getSize() < 0 || handler.changesContentSize()) {
                rh.getWebHeader().removeHeader("Content-Length");
                rh.getWebHeader().setHeader("Transfer-Encoding", "chunked");
            } else {
                setChunking(false);
            }
        } else {
            if (keepalive) {
                rh.getWebHeader().setHeader("Proxy-Connection", "Keep-Alive");
                rh.getWebHeader().setHeader("Connection", "Keep-Alive");
            } else {
                rh.getWebHeader().setHeader("Proxy-Connection", "close");
                rh.getWebHeader().setHeader("Connection", "close");
            }
        }
    }

    private class TDL implements TunnelDoneListener {
        private final RequestHandler rh;

        public TDL(final RequestHandler rh) {
            this.rh = rh;
        }

        @Override
        public void tunnelClosed() {
            logAndClose(rh);
        }
    }

    private void setupChunkedContent() {
        status = "Request read, reading chunked data";
        clientResourceHandler =
                new ChunkedContentTransferHandler(this, requestHandle, tlh);
    }

    private void setupClientResourceHandler(final long dataSize) {
        status = "Request read, reading client resource data";
        clientResourceHandler =
                new ContentTransferHandler(this, requestHandle, dataSize, tlh);
    }

    private void readMultiPart(final String ct) {
        status = "Request read, reading multipart data";
        // Content-Type: multipart/byteranges; boundary=B-qpuvxclkeavxeywbqupw
        if (ct.startsWith("multipart/byteranges")) {
            clientResourceHandler =
                    new MultiPartTransferHandler(this, requestHandle, tlh, ct);
        }
    }

    /** Send an error (400 Bad Request) to the client.
     * @param status the status code of the error.
     * @param message the error message to tell the client.
     */
    public void doError(final int status, final String message) {
        this.statusCode = Integer.toString(500);
        final HttpHeader header =
                responseHandler.get400(new IOException(message));
        sendAndClose(header);
    }

    /** Send an error (400 Bad Request or 504) to the client.
     * @param e the exception to tell the client.
     */
    private void doGateWayTimeout(final Exception e) {
        this.statusCode = "504";
        extraInfo = (extraInfo != null ?
                     extraInfo + e.toString() :
                     e.toString());
        final HttpHeader header =
                responseHandler.get504(request.getRequestURI(), e);
        sendAndClose(header);
    }

    private void checkAndHandleSSL(final BufferHandle bh) {
        status = "Handling ssl request";
        final SSLHandler sslh = new SSLHandler(proxy, this, request, tlh);
        if (sslh.isAllowed()) {
            final HttpHeaderFilterer filterer = proxy.getHttpHeaderFilterer();
            final HttpHeader badresponse = filterer.filterConnect(this, channel, request);
            if (badresponse != null) {
                statusCode = badresponse.getStatusCode();
                // Send response and close
                sendAndClose(badresponse);
            } else {
                sslh.handle(channel, bh);
            }
        } else {
            final HttpHeader badresponse = responseHandler.get403();
            sendAndClose(badresponse);
        }
    }

    /** Get the SocketChannel to the client
     * @return the SocketChannel connected to the client
     */
    public SocketChannel getChannel() {
        return channel;
    }

    /**
     * @return the NioHandler that this connection is using
     */
    public NioHandler getNioHandler() {
        return proxy.getNioHandler();
    }

    /**
     * @return the HttProxy that this connection is serving
     */
    public HttpProxy getProxy() {
        return proxy;
    }

    /**
     * @return the BufferHandler that this connection is using
     */
    public BufferHandler getBufferHandler() {
        return bufHandler;
    }

    private void closeDown() {
        Closer.close(channel, logger);
        if (!requestHandle.isEmpty()) {
            // empty the buffer...
            final ByteBuffer buf = requestHandle.getBuffer();
            buf.position(buf.limit());
        }
        requestHandle.possiblyFlush();
        proxy.removeCurrentConnection(this);
    }

    /**
     * @return the Counter that keeps count of operations for this connection.
     */
    public Counter getCounter() {
        return proxy.getCounter();
    }

    /** Resets the statuses for this connection.
     */
    private void clearStatuses() {
        status         = "Reading request";
        started        = System.currentTimeMillis();
        request        = null;
        keepalive      = true;
        chunk          = true;
        userName       = null;
        password       = null;
        requestLine    = "?";
        statusCode     = "200";
        extraInfo      = null;
        contentLength  = "-";
        clientResourceHandler = null;
    }

    /** Set keepalive to a new value. Note that keepalive can only be
     *    promoted down.
     * @param keepalive the new keepalive value.
     */
    public void setKeepalive(final boolean keepalive) {
        this.keepalive = (this.keepalive && keepalive);
    }

    /** Get the name of the user that is currently authorized.
     * @return a username, may be null if the user is not know/authorized
     */
    public String getUserName() {
        return userName;
    }

    /** Set the name of the currently authenticated user (for basic proxy auth)
     * @param userName the name of the current user
     */
    public void setUserName(final String userName) {
        this.userName = userName;
    }

    /** Get the name of the user that is currently authorized.
     * @return a username, may be null if the user is not know/authorized
     */
    public String getPassword() {
        return password;
    }

    /** Set the password of the currently authenticated user (for basic proxy auth)
     * @param password the password that was used for authentication
     */
    public void setPassword(final String password) {
        this.password = password;
    }

    /** Get the request line of the request currently being handled
     * @return the request line for the current request
     */
    public String getRequestLine() {
        return requestLine;
    }

    /** Get the current request uri.
     *  This will get the uri from the request header.
     * @return the uri of the current request
     */
    public String getRequestURI() {
        return request.getRequestURI();
    }

    /** Get the http version that the client used.
     *  We modify the request header to hold HTTP/1.1 since that is
     *  what rabbit uses, but the real client may have sent a 1.0 header.
     * @return the request http version
     */
    public String getRequestVersion() {
        return requestVersion;
    }

    /** Get the current status of this request 
     * @return the current status
     */
    public String getStatus() {
        return status;
    }

    // For logging
    String getStatusCode() {
        return statusCode;
    }

    /**
     * @return the content length of the current request
     */
    public String getContentLength() {
        return contentLength;
    }

    /** Get the client resource handler, that is the handler of any content
     *  the client is submitting (POSTED data, file uploads etc.)
     * @return the ClientResourceHandler for the current request
     */
    public ClientResourceHandler getClientResourceHandler() {
        return clientResourceHandler;
    }

    /** Get the extra information associated with the current request.
     * @return the currently set extra info or null if no such info is set.
     */
    public String getExtraInfo() {
        return extraInfo;
    }

    /** Set the extra info.
     * @param info the new info.
     */
    public void setExtraInfo(final String info) {
        this.extraInfo = info;
    }

    /** Get the time the current request was started. 
     * @return the start time for the current request
     */
    public long getStarted() {
        return started;
    }

    /** Set the chunking option.
     * @param b if true this connection should use chunking.
     */
    public void setChunking(final boolean b) {
        chunk = b;
    }

    /** Get the chunking option.
     * @return if this connection is using chunking.
     */
    public boolean getChunking() {
        return chunk;
    }

    /** Set the content length of the response.
     * @param contentLength the new content length.
     */
    public void setContentLength(final String contentLength) {
        this.contentLength = contentLength;
    }

    /** Set the status code for the current request
     * @param statusCode the new status code
     */
    public void setStatusCode(final String statusCode) {
        this.statusCode = statusCode;
    }

    // Set status and content length
    private void setStatusesFromHeader(final HttpHeader header) {
        statusCode = header.getStatusCode();
        final String cl = header.getHeader("Content-Length");
        if (cl != null) {
            contentLength = cl;
        }
    }

    void sendAndRestart(final HttpHeader header) {
        status = "Sending response.";
        setStatusesFromHeader(header);
        if (!keepalive) {
            sendAndClose(header);
        } else {
            final HttpHeaderSentListener sar = new SendAndRestartListener();
            try {
                final HttpHeaderSender hhs =
                        new HttpHeaderSender(channel, getNioHandler(),
                                             tlh.getClient(),
                                             header, false, sar);
                hhs.sendHeader();
            } catch (IOException e) {
                logger.log(Level.WARNING,
                           "IOException when sending header", e);
                closeDown();
            }
        }
    }

    private abstract class SendAndDoListener implements HttpHeaderSentListener {
        @Override
        public void timeout() {
            status = "Response sending timed out, logging and closing.";
            logger.info("Timeout when sending http header");
            logAndClose(null);
        }

        @Override
        public void failed(final Exception e) {
            status =
                    "Response sending failed: " + e + ", logging and closing.";
            logger.log(Level.INFO, "Exception when sending http header", e);
            logAndClose(null);
        }
    }

    private class SendAndRestartListener extends SendAndDoListener {
        @Override
        public void httpHeaderSent() {
            readRequest();
        }
    }

    /** Send a request and then close this connection.
     * @param header the HttpHeader to send before closing down.
     */
    public void sendAndClose(final HttpHeader header) {
        status = "Sending response and closing.";
        // Set status and content length
        setStatusesFromHeader(header);
        keepalive = false;
        final HttpHeaderSentListener scl = new SendAndCloseListener();
        try {
            final HttpHeaderSender hhs =
                    new HttpHeaderSender(channel, getNioHandler(),
                                         tlh.getClient(), header, false, scl);
            hhs.sendHeader();
        } catch (IOException e) {
            logger.log(Level.WARNING, "IOException when sending header", e);
            closeDown();
        }
    }

    /** Log the current request and close/end this connection
     * @param rh the current RequestHandler
     */
    public void logAndClose(final RequestHandler rh) {
        if (rh != null && rh.getWebConnection() != null) {
            proxy.releaseWebConnection(rh.getWebConnection());
        }
        closeDown();
    }

    /** Log the current request and start to listen for a new request.
     */
    public void logAndRestart() {
        if (keepalive) {
            readRequest();
        } else {
            closeDown();
        }
    }

    private class SendAndCloseListener extends SendAndDoListener {
        @Override
        public void httpHeaderSent() {
            status = "Response sent, logging and closing.";
            logAndClose(null);
        }
    }

    /** Get the HttpGenerator that this connection uses when it needs to
     *  generate a custom respons header and resource.
     * @return the current HttpGenerator
     */
    public HttpGenerator getHttpGenerator() {
        return responseHandler;
    }
}
