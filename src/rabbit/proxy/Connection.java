package rabbit.proxy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import rabbit.cache.Cache;
import rabbit.cache.CacheEntry;
import rabbit.handler.BaseHandler;
import rabbit.handler.Handler;
import rabbit.handler.MultiPartHandler;
import rabbit.http.HttpDateParser;
import rabbit.http.HttpHeader;
import rabbit.httpio.HttpHeaderListener;
import rabbit.httpio.HttpHeaderReader;
import rabbit.httpio.HttpHeaderSender;
import rabbit.httpio.HttpHeaderSentListener;
import rabbit.httpio.RequestLineTooLongException;
import rabbit.io.BufferHandle;
import rabbit.io.BufferHandler;
import rabbit.io.CacheBufferHandle;
import rabbit.io.Closer;
import rabbit.nio.NioHandler;
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
    private BufferHandler bufHandler;

    /** The proxy we are serving */
    private final HttpProxy proxy;

    /** The current status of this connection. */
    private String status;

    /** The time this connection was started. */
    private long started;

    private boolean  keepalive      = true;
    private boolean  meta           = false;
    private boolean  chunk          = true;
    private boolean  mayUseCache    = true;
    private boolean  mayCache       = true;
    private boolean  mayFilter      = true;
    private boolean  mustRevalidate = false;
    private boolean  addedINM       = false;
    private boolean  addedIMS       = false;

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

    private StandardResponseHeaders responseHandler;

    private TrafficLoggerHandler tlh = new TrafficLoggerHandler ();

    private final Logger logger = Logger.getLogger (getClass ().getName ());

    public Connection (ConnectionId id,	SocketChannel channel,
		       HttpProxy proxy, BufferHandler bufHandler) {
	this.id = id;
	this.channel = channel;
	this.proxy = proxy;
	this.requestHandle = new CacheBufferHandle (bufHandler);
	this.bufHandler = bufHandler;
	proxy.addCurrentConnection (this);
	responseHandler =
	    new StandardResponseHeaders (proxy.getServerIdentity (), this);
    }

    // For logging and status
    public ConnectionId getId () {
	return id;
    }

    /** Read a request.
     */
    public void readRequest () {
	clearStatuses ();
	try {
	    channel.socket ().setTcpNoDelay (true);
	    HttpHeaderListener clientListener = new RequestListener ();
	    HttpHeaderReader hr =
		new HttpHeaderReader (channel, requestHandle, getNioHandler (),
				      tlh.getClient (), true,
				      proxy.getStrictHttp (), clientListener);
	    hr.readRequest ();
	} catch (Throwable ex) {
	    handleFailedRequestRead (ex);
	}
    }

    private void handleFailedRequestRead (Throwable t) {
	if (t instanceof RequestLineTooLongException) {
	    HttpHeader err = getHttpGenerator ().get414 ();
	    // Send response and close
	    sendAndClose (err);
	} else {
	    logger.log (Level.INFO, "Exception when reading request", t);
	    closeDown ();
	}
    }

    private class RequestListener implements HttpHeaderListener {
	public void httpHeaderRead (HttpHeader header, BufferHandle bh,
				    boolean keepalive, boolean isChunked,
				    long dataSize) {
	    setKeepalive (keepalive);
	    requestRead (header, bh, isChunked, dataSize);
	}

	public void closed () {
	    closeDown ();
	}

	public void timeout () {
	    closeDown ();
	}

	public void failed (Exception e) {
	    handleFailedRequestRead (e);
	}
    }

    private void handleInternalError (Throwable t) {
	extraInfo =
	    extraInfo != null ? extraInfo + t.toString () : t.toString ();
	logger.log (Level.WARNING, "Internal Error", t);
	HttpHeader internalError = getHttpGenerator ().get500 (t);
	// Send response and close
	sendAndClose (internalError);
    }

    private void requestRead (HttpHeader request, BufferHandle bh,
			      boolean isChunked, long dataSize) {
	if (request == null) {
	    logger.warning ("Got a null request");
	    closeDown ();
	    return;
	}
	status = "Request read, processing";
	this.request = request;
	this.requestHandle = bh;
	requestVersion = request.getHTTPVersion ();
	if (requestVersion == null) {
	    // TODO: fix http/0.9 handling.
	    logger.info ("bad header read: " + request);
	    closeDown ();
	    return;
	}
	requestVersion = requestVersion.toUpperCase ();
	request.addHeader ("Via", requestVersion + " RabbIT");

	requestLine = request.getRequestLine ();
	getCounter ().inc ("Requests");

	try {
	    // SSL requests are special in a way...
	    // Don't depend upon being able to build URLs from the header...
	    if (request.isSSLRequest ()) {
		checkAndHandleSSL (bh);
		return;
	    }

	    // Now set up handler of any posted data.
	    // is the request resource chunked?
	    if (isChunked) {
		setupChunkedContent ();
	    } else {
		// no? then try regular data
		String ct = null;
		ct = request.getHeader ("Content-Type");
		if (hasRegularContent (request, ct, dataSize))
		    setupClientResourceHandler (dataSize);
		else
		    // still no? then try multipart
		    if (ct != null)
			readMultiPart (ct);
	    }

	    filterAndHandleRequest ();
	} catch (Throwable t) {
	    handleInternalError (t);
	}
    }

    private boolean hasRegularContent (HttpHeader request, String ct,
				       long dataSize) {
	if (request.getContent () != null)
	    return true;
	if (ct != null && ct.startsWith ("multipart/byteranges"))
	    return false;
	return dataSize > -1;
    }

    /** Filter the request and handle it.
     * @param header the request
     */
    // TODO: filtering here may block! be prepared to run filters in a
    // TODO: separate thread.
    private void filterAndHandleRequest () {
	// Filter the request based on the header.
	// A response means that the request is blocked.
	// For ad blocking, bad header configuration (http/1.1 correctness) ...
	HttpHeaderFilterer filterer = proxy.getHttpHeaderFilterer ();
	HttpHeader badresponse = filterer.filterHttpIn (this, channel, request);
	if (badresponse != null) {
	    statusCode = badresponse.getStatusCode ();
	    // Send response and close
	    sendAndClose (badresponse);
	} else {
	    if (getMeta ())
		handleMeta ();
	    else
		handleRequest ();
	}
    }

    /** Handle a meta page.
     */
    private void handleMeta () {
	status = "Handling meta page";
	MetaHandlerHandler mhh = new MetaHandlerHandler ();
	try {
	    mhh.handleMeta (this, request, tlh.getProxy (), tlh.getClient ());
	} catch (IOException ex) {
	    logAndClose (null);
	}
    }

    private void checkNoStore (CacheEntry<HttpHeader, HttpHeader> entry) {
	if (entry == null)
	    return;
	List<String> ccs = request.getHeaders ("Cache-Control");
	int ccl = ccs.size ();
	for (int i = 0; i < ccl; i++)
	    if (ccs.get (i).equals ("no-store"))
		proxy.getCache ().remove (entry.getKey ());
    }

    private boolean checkMaxAge (RequestHandler rh) {
	return rh.getCond ().checkMaxAge (this, rh.getDataHook (), rh);
    }

    /** Handle a request by getting the datastream (from the cache or the web).
     *  After getting the handler for the mimetype, send it.
     */
    private void handleRequest () {
	status = "Handling request";
	final RequestHandler rh = new RequestHandler (this);
	if (proxy.getCache ().getMaxSize () > 0) {
	    // memory consistency is guarded by the underlying SynchronousQueue
	    getNioHandler ().runThreadTask (new Runnable () {
		    public void run () {
			fillInCacheEntries (rh);
		    }
		});
	} else {
	    handleRequestBottom (rh);
	}
    }

    private void fillInCacheEntries (final RequestHandler rh) {
	status = "Handling request - checking cache";
	Cache<HttpHeader, HttpHeader> cache = proxy.getCache ();
	String method = request.getMethod ();
	if (!method.equals ("GET") && !method.equals ("HEAD"))
	    cache.remove (request);

	rh.setEntry (cache.getEntry (request));
	if (rh.getEntry () != null)
	    rh.setDataHook (rh.getEntry ().getDataHook (proxy.getCache ()));

	checkNoStore (rh.getEntry ());
	// Check if cached item is too old
	if (!rh.getCond ().checkMaxStale (request, rh) && checkMaxAge (rh))
	    setMayUseCache (false);

	// Add headers to send If-None-Match, or If-Modified-Since
	rh.setConditional (rh.getCond ().checkConditional (this, request, rh,
							   mustRevalidate));
	if (partialContent (rh))
	    fillupContent ();
	checkIfRange (rh);

	boolean mc = getMayCache ();
	// in cache?
	if (getMayUseCache () && rh.getEntry () != null) {
	    CacheChecker cc = new CacheChecker ();
	    if (cc.checkCachedEntry (this, request, rh))
		return;
	}
	if (rh.getContent () == null) {
	    // Ok cache did not have a usable resource,
	    // reset value to one before we thought we could use cache...
	    mayCache = mc;
	}

	handleRequestBottom (rh);
    }

    private void handleRequestBottom (final RequestHandler rh) {
	if (rh.getContent () == null) {
	    status = "Handling request - setting up web connection";
	    // no usable cache entry so get the resource from the net.
	    SWC swc = new SWC (this, request, tlh, clientResourceHandler, rh);
	    swc.establish ();
	} else {
	    resourceEstablished (rh);
	}
    }

    void webConnectionSetupFailed (RequestHandler rh, Exception cause) {
	if (cause instanceof UnknownHostException)
	    // do we really want this in the log?
	    logger.warning (cause.toString () + ": " + 
			    request.getRequestURI ());
	else
	    logger.warning ("Failed to set up web connection to: " +
			    request.getRequestURI () + ", cause: " + cause);
	tryStaleEntry (rh, cause);
    }

    private void setMayCacheFromCC (RequestHandler rh) {
	HttpHeader resp = rh.getWebHeader ();
	for (String val : resp.getHeaders ("Cache-Control")) {
	    if ("public".equals (val)
		|| "must-revalidate".equals (val)
		|| val.startsWith ("s-maxage=")) {
		String auth = request.getHeader ("Authorization");
		if (auth != null) {
		    // TODO this ignores no-store and a few other things...
		    mayCache = true;
		    break;
		}
	    }
	}
    }

    /** Check if we must tunnel a request.
     *  Currently will only check if the Authorization starts with NTLM or Negotiate.
     * @param rh the request handler.
     */
    protected boolean mustTunnel (RequestHandler rh) {
	String auth = request.getHeader ("Authorization");
	return auth != null &&
	    (auth.startsWith ("NTLM") || auth.startsWith ("Negotiate"));
    }

    void webConnectionEstablished (RequestHandler rh) {
	getProxy ().markForPipelining (rh.getWebConnection ());
	setMayCacheFromCC (rh);
	resourceEstablished (rh);
    }

    private void tunnel (RequestHandler rh) {
	status = "Handling request - tunneling";
	try {
	    TunnelDoneListener tdl = new TDL (rh);
	    SocketChannel webChannel = rh.getWebConnection ().getChannel ();
	    Tunnel tunnel =
		new Tunnel (getNioHandler (), channel, requestHandle,
			    tlh.getClient (), webChannel,
			    rh.getWebHandle (), tlh.getNetwork (), tdl);
	    tunnel.start ();
	} catch (IOException ex) {
	    logAndClose (rh);
	}
    }

    private void resourceEstablished (RequestHandler rh) {
	status = "Handling request - got resource";
	try {
	    // and now we filter the response header if any.
	    if (!request.isDot9Request ()) {
		if (mustTunnel (rh)) {
		    tunnel (rh);
		    return;
		}

		String status = rh.getWebHeader ().getStatusCode ().trim ();

		// Check if the cached Date header is newer,
		// indicating that we should not cache.
		if (!rh.getCond ().checkStaleCache (request, this, rh))
		    setMayCache (false);

		CacheChecker cc = new CacheChecker ();
		cc.removeOtherStaleCaches (request, rh.getWebHeader (),
					   proxy.getCache ());
		if (status.equals ("304")) {
		    NotModifiedHandler nmh = new NotModifiedHandler ();
		    nmh.updateHeader (rh);
		    if (rh.getEntry () != null) {
			proxy.getCache ().entryChanged (rh.getEntry (),
							request, rh.getDataHook ());
		    }
		}

		// Check that the cache entry has expected header
		// returns null for a good cache entry
		HttpHeader bad =
		    cc.checkExpectations (this, request, rh.getWebHeader ());
		if (bad == null) {
		    HttpHeaderFilterer filterer =
			proxy.getHttpHeaderFilterer ();
		    // Run output filters on the header
		    bad = filterer.filterHttpOut (this, channel, rh.getWebHeader ());
		}
		if (bad != null) {
		    // Bad cache entry or this request is blocked
		    rh.getContent ().release ();
		    // Send error response and close
		    sendAndClose (bad);
		    return;
		}

		if (rh.isConditional () && rh.getEntry () != null
		    && status.equals ("304")) {
		    // Try to setup a resource from the cache
		    if (handleConditional (rh)) {
			return;
		    }
		} else if (status.length () > 0) {
		    if (status.equals ("304") || status.equals ("204")
			|| status.charAt (0) == '1') {
			rh.getContent ().release ();
			// Send success response and close
			sendAndClose (rh.getWebHeader ());
			return;
		    }
		}
	    }

	    setHandlerFactory (rh);
	    status = "Handling request - " +
		rh.getHandlerFactory ().getClass ().getName ();
	    Handler handler =
		rh.getHandlerFactory ().getNewInstance (this, tlh,
							request, requestHandle,
							rh.getWebHeader (),
							rh.getContent (),
							getMayCache (),
							getMayFilter (),
							rh.getSize ());
	    if (handler == null) {
		doError (500, "Something fishy with that handler....");
	    } else {
		finalFixesOnWebHeader (rh, handler);
		// HTTP/0.9 does not support HEAD, so webheader should be valid.
		if (request.isHeadOnlyRequest ()) {
		    rh.getContent ().release ();
		    sendAndRestart (rh.getWebHeader ());
		} else {
		    handler.handle ();
		}
	    }
	} catch (Throwable t) {
	    handleInternalError (t);
	}
    }

    private void finalFixesOnWebHeader (RequestHandler rh, Handler handler) {
	if (chunk) {
	    if (rh.getSize () < 0 || handler.changesContentSize ()) {
		rh.getWebHeader ().removeHeader ("Content-Length");
		rh.getWebHeader ().setHeader ("Transfer-Encoding", "chunked");
	    } else {
		setChunking (false);
	    }
	} else {
	    if (getKeepalive ()) {
		rh.getWebHeader ().setHeader ("Proxy-Connection", "Keep-Alive");
		rh.getWebHeader ().setHeader ("Connection", "Keep-Alive");
	    } else {
		rh.getWebHeader ().setHeader ("Proxy-Connection", "close");
		rh.getWebHeader ().setHeader ("Connection", "close");
	    }
	}
    }

    private void setHandlerFactory (RequestHandler rh) {
	if (rh.getHandlerFactory () == null) {
	    String ct = null;
	    if (rh.getWebHeader () != null) {
		ct = rh.getWebHeader ().getHeader ("Content-Type");
		if (ct != null) {
		    ct = ct.toLowerCase ();
		    // remove some white spaces for easier configuration.
		    // "text/html; charset=iso-8859-1"
		    // "text/html;charset=iso-8859-1"
		    ct = ct.replace ("; ", ";");
		    if (getMayFilter ())
			rh.setHandlerFactory (proxy.getHandlerFactory (ct));
		    if (rh.getHandlerFactory () == null
			&& ct.startsWith ("multipart/byteranges"))
			rh.setHandlerFactory (new MultiPartHandler ());
		}
	    }
	    if (rh.getHandlerFactory () == null) {              // still null
		logger.fine ("Using BaseHandler for " + ct);
		rh.setHandlerFactory (new BaseHandler ());   // fallback...
	    }
	}
    }

    private boolean handleConditional (RequestHandler rh) throws IOException {
	HttpHeader cachedHeader = rh.getDataHook ();
	rh.getContent ().release ();

	if (addedINM)
	    request.removeHeader ("If-None-Match");
	if (addedIMS)
	    request.removeHeader ("If-Modified-Since");

	if (checkWeakEtag (cachedHeader, rh.getWebHeader ())) {
	    NotModifiedHandler nmh = new NotModifiedHandler ();
	    nmh.updateHeader (rh);
	    setMayCache (false);
	    try {
		HttpHeader res304 = nmh.is304 (request, this, rh);
		if (res304 != null) {
		    sendAndClose (res304);
		    return true;
		}
		// Try to setup a resource from the cache
		setupCachedEntry (rh);
	    } catch (IOException e) {
		logger.log (Level.WARNING,
			    "Conditional request: IOException (" +
			    request.getRequestURI (),
			    e);
	    }
	} else {
	    // retry...
	    request.removeHeader ("If-None-Match");
	    proxy.getCache ().remove (request);
	    handleRequest ();
	    return true;
	}

	// send the cached entry.
	return false;
    }

    private class TDL implements TunnelDoneListener {
	private RequestHandler rh;

	public TDL (RequestHandler rh) {
	    this.rh = rh;
	}

	public void tunnelClosed () {
	    logAndClose (rh);
	}
    }

    private void tryStaleEntry (RequestHandler rh, Exception e) {
	// do we have a stale entry?
	if (rh.getEntry () != null && rh.isConditional () && !mustRevalidate)
	    handleStaleEntry (rh);
	else
	    doError (504, e);
    }

    private void handleStaleEntry (RequestHandler rh) {
	setMayCache (false);
	try {
	    setupCachedEntry (rh);
	    rh.getWebHeader ().addHeader ("Warning",
				    "110 RabbIT \"Response is stale\"");
	    resourceEstablished (rh);
	} catch (IOException ex) {
	    doError (504, ex);
	    return;
	}
    }

    // Setup a resource from the cache
    HttpHeader setupCachedEntry (RequestHandler rh) throws IOException {
	SCC swc = new SCC (this, request, rh);
	HttpHeader ret = swc.establish ();
	return ret;
    }

    private void setupChunkedContent () throws IOException {
	status = "Request read, reading chunked data";
	setMayUseCache (false);
	setMayCache (false);
	clientResourceHandler =
	    new ChunkedContentTransferHandler (this, requestHandle, tlh);
    }

    private void setupClientResourceHandler (long dataSize) {
	status = "Request read, reading client resource data";
	setMayUseCache (false);
	setMayCache (false);
	clientResourceHandler =
	    new ContentTransferHandler (this, requestHandle, dataSize, tlh);
    }

    private void readMultiPart (String ct) {
	status = "Request read, reading multipart data";
	// Content-Type: multipart/byteranges; boundary=B-qpuvxclkeavxeywbqupw
	if (ct.startsWith ("multipart/byteranges")) {
	    setMayUseCache (false);
	    setMayCache (false);

	    clientResourceHandler =
		new MultiPartTransferHandler (this, requestHandle, tlh, ct);
	}
    }

    private boolean partialContent (RequestHandler rh) {
	if (rh.getEntry () == null)
	    return false;
	String method = request.getMethod ();
	if (!method.equals ("GET"))
	    return false;
	HttpHeader resp = rh.getDataHook ();
	String realLength = resp.getHeader ("RabbIT-Partial");
	return (realLength != null);
    }

    private void fillupContent () {
	setMayUseCache (false);
	setMayCache (true);
	// TODO: if the need arise, think about implementing smart partial updates.
    }

    private void checkIfRange (RequestHandler rh) {
	if (rh.getEntry () == null)
	    return;
	String ifRange = request.getHeader ("If-Range");
	if (ifRange == null)
	    return;
	String range = request.getHeader ("Range");
	if (range == null)
	    return;
	Date d = HttpDateParser.getDate (ifRange);
	HttpHeader oldresp = rh.getDataHook ();
	if (d == null) {
	    // we have an etag...
	    String etag = oldresp.getHeader ("Etag");
	    if (etag == null || !checkWeakEtag (etag, ifRange))
		setMayUseCache (false);
	}
    }

    /** Send an error (400 Bad Request) to the client.
     * @param status the status code of the error.
     * @param message the error message to tell the client.
     */
    void doError (int status, String message) {
	this.statusCode = Integer.toString (status);
	HttpHeader header = responseHandler.getHeader ("HTTP/1.0 400 Bad Request");
	StringBuilder error =
	    new StringBuilder (HtmlPage.getPageHeader (this, "400 Bad Request") +
			       "Unable to handle request:<br><b>" +
			       message +
			       "</b></body></html>\n");
	header.setContent (error.toString ());
	sendAndClose (header);
    }

    /** Send an error (400 Bad Request or 504) to the client.
     * @param statuscode the status code of the error.
     * @param e the exception to tell the client.
     */
    private void doError (int statuscode, Exception e) {
	String message = "";
	boolean dnsError = (e instanceof UnknownHostException);
	this.statusCode = Integer.toString (statuscode);
	extraInfo = (extraInfo != null ?
		     extraInfo + e.toString () :
		     e.toString ());
	HttpHeader header = null;
	if (!dnsError) {
	    StringWriter sw = new StringWriter ();
	    PrintWriter ps = new PrintWriter (sw);
	    e.printStackTrace (ps);
	    message = sw.toString ();
	}
	if (statuscode == 504)
	    header = getHttpGenerator ().get504 (e, requestLine);
	else
	    header = getHttpGenerator ().getHeader ("HTTP/1.0 400 Bad Request");

	StringBuilder sb = new StringBuilder ();
	sb.append (HtmlPage.getPageHeader (this, statuscode + " " +
					   header.getReasonPhrase ()));
	if (dnsError)
	    sb.append ("Server not found");
	else
	    sb.append ("Unable to handle request");
	sb.append (":<br><b>" + e.getMessage () +
		   (header.getContent () != null ?
		    "<br>" + header.getContent () :
		    "") +
		   "</b><br><xmp>" + message + "</xmp></body></html>\n");
	header.setContent (sb.toString ());
	sendAndClose (header);
    }

    private void checkAndHandleSSL (BufferHandle bh) {
	status = "Handling ssl request";
	SSLHandler sslh = new SSLHandler (proxy, this, request, tlh);
	if (sslh.isAllowed ()) {
	    sslh.handle (channel, bh);
	} else {
	    HttpHeader badresponse = responseHandler.get403 ();
	    sendAndClose (badresponse);
	}
    }

    public SocketChannel getChannel () {
	return channel;
    }

    public NioHandler getNioHandler () {
	return proxy.getNioHandler ();
    }

    public HttpProxy getProxy () {
	return proxy;
    }

    public BufferHandler getBufferHandler () {
	return bufHandler;
    }

    private void closeDown () {
	Closer.close (channel, logger);
	if (!requestHandle.isEmpty ()) {
	    // empty the buffer...
	    ByteBuffer buf = requestHandle.getBuffer ();
	    buf.position (buf.limit ());
	}
	requestHandle.possiblyFlush ();
	proxy.removeCurrentConnection (this);
    }

    private ConnectionLogger getConnectionLogger () {
	return proxy.getConnectionLogger ();
    }

    Counter getCounter () {
	return proxy.getCounter ();
    }

    /** Resets the statuses for this connection.
     */
    private void clearStatuses () {
	status         = "Reading request";
	started        = System.currentTimeMillis ();
	request        = null;
	keepalive      = true;
	meta           = false;
	chunk          = true;
	mayUseCache    = true;
	mayCache       = true;
	mayFilter      = true;
	mustRevalidate = false;
	addedINM       = false;
	addedIMS       = false;
	userName       = null;
	password       = null;
	requestLine    = "?";
	statusCode     = "200";
	extraInfo      = null;
	contentLength  = "-";
	clientResourceHandler = null;
    }

    /** Set keepalive to a new value. Note that keepalive can only be
     *	promoted down.
     * @param keepalive the new keepalive value.
     */
    public void setKeepalive (boolean keepalive) {
	this.keepalive = (this.keepalive && keepalive);
    }

    /** Get the keepalive value.
     * @return true if keepalive should be done, false otherwise.
     */
    private boolean getKeepalive () {
	return keepalive;
    }

    public String getUserName () {
	return userName;
    }

    public void setUserName (String userName) {
	this.userName = userName;
    }

    public String getPassword () {
	return password;
    }

    public void setPassword (String password) {
	this.password = password;
    }

    // For logging and status
    public String getRequestLine () {
	return requestLine;
    }

    /** Get the current request uri.
     *  This will get the uri from the request header.
     */
    public String getRequestURI () {
	return request.getRequestURI();
    }

    // Get debug info for use in 500 error response
    String getDebugInfo () {
	return
	    "status: " + getStatus ()  + "\n" +
	    "started: " + new Date (getStarted ()) + "\n" +
	    "keepalive: " + getKeepalive () + "\n" +
	    "meta: " + getMeta () + "\n" +
	    "mayusecache: " + getMayUseCache () + "\n" +
	    "maycache: " + getMayCache () + "\n" +
	    "mayfilter: " + getMayFilter () + "\n"+
	    "requestline: " + getRequestLine () + "\n" +
	    "statuscode: " + getStatusCode () + "\n" +
	    "extrainfo: " + getExtraInfo () + "\n" +
	    "contentlength: " + getContentLength () + "\n";
    }

    /** Get the http version that the client used.
     *  We modify the request header to hold HTTP/1.1 since that is
     *  what rabbit uses, but the real client may have sent a 1.0 header.
     */
    String getRequestVersion () {
	return requestVersion;
    }

    // For logging and status
    public String getStatus () {
	return status;
    }

    // For logging
    String getStatusCode () {
	return statusCode;
    }

    public String getContentLength () {
	return contentLength;
    }

    public String getExtraInfo () {
	return extraInfo;
    }

    /** Set the extra info.
     * @param info the new info.
     */
    public void setExtraInfo (String info) {
	this.extraInfo = info;
    }

    /** Get the time this connection was started. */
    public long getStarted () {
	return started;
    }

    /** Set the chunking option.
     * @param b if true this connection should use chunking.
     */
    public void setChunking (boolean b) {
	chunk = b;
    }

    /** Get the chunking option.
     * @return if this connection is using chunking.
     */
    public boolean getChunking () {
	return chunk;
    }

    /** Get the state of this request.
     * @return true if this is a metapage request, false otherwise.
     */
    public boolean getMeta () {
	return meta;
    }

    /** Set the state of this request.
     * @param meta true if this request is a metapage request, false otherwise.
     */
    public void setMeta (boolean meta) {
	this.meta = meta;
    }

    /** Set the state of this request. This can only be promoted down..
     * @param useCache true if we may use the cache for this request,
     *        false otherwise.
     */
    public void setMayUseCache (boolean useCache) {
	mayUseCache = mayUseCache && useCache;
    }

    /** Get the state of this request.
     * @return true if we may use the cache for this request, false otherwise.
     */
    private boolean getMayUseCache () {
	return mayUseCache;
    }

    /** Set the state of this request. This can only be promoted down.
     * @param cacheAllowed true if we may cache the response, false otherwise.
     */
    public void setMayCache (boolean cacheAllowed) {
	mayCache = cacheAllowed && mayCache;
    }

    /** Get the state of this request.
     * @return true if we may cache the response, false otherwise.
     */
    private boolean getMayCache () {
	return mayCache;
    }

    /** Get the state of this request. This can only be promoted down.
     * @param filterAllowed true if we may filter the response, false otherwise.
     */
    public void setMayFilter (boolean filterAllowed) {
	mayFilter = filterAllowed && mayFilter;
    }

    /** Get the state of the request.
     * @return true if we may filter the response, false otherwise.
     */
    public boolean getMayFilter () {
	return mayFilter;
    }

    void setAddedINM (boolean b) {
	addedINM = b;
    }

    void setAddedIMS (boolean b) {
	addedIMS = b;
    }

    public void setMustRevalidate (boolean b) {
	mustRevalidate = b;
    }

    /** Set the content length of the response.
     * @param contentLength the new content length.
     */
    public void setContentLength (String contentLength) {
	this.contentLength = contentLength;
    }

    public void setStatusCode (String statusCode) {
	this.statusCode = statusCode;
    }

    // Set status and content length
    private void setStatusesFromHeader (HttpHeader header) {
	statusCode = header.getStatusCode ();
	String cl = header.getHeader ("Content-Length");
	if (cl != null)
	    contentLength  = cl;
    }

    void sendAndRestart (HttpHeader header) {
	status = "Sending response.";
	setStatusesFromHeader (header);
	if (!keepalive) {
	    sendAndClose (header);
	} else {
	    HttpHeaderSentListener sar = new SendAndRestartListener ();
	    try {
		HttpHeaderSender hhs =
		    new HttpHeaderSender (channel, getNioHandler (),
					  tlh.getClient (),
					  header, false, sar);
		hhs.sendHeader ();
	    } catch (IOException e) {
		logger.log (Level.WARNING,
			    "IOException when sending header", e);
		closeDown ();
	    }
	}
    }

    boolean useFullURI () {
	return proxy.isProxyConnected ();
    }

    private class SendAndRestartListener implements HttpHeaderSentListener {
	public void httpHeaderSent () {
	    logConnection ();
	    readRequest ();
	}

	public void timeout () {
	    logger.info ("Timeout when sending http header");
	    logAndClose (null);
	}

	public void failed (Exception e) {
	    logger.log (Level.INFO, "Exception when sending http header", e);
	    logAndClose (null);
	}
    }

    // Send response and close
    void sendAndClose (HttpHeader header) {
	status = "Sending response and closing.";
	// Set status and content length
	setStatusesFromHeader (header);
	keepalive = false;
	HttpHeaderSentListener scl = new SendAndCloseListener ();
	try {
	    HttpHeaderSender hhs =
		new HttpHeaderSender (channel, getNioHandler (),
				      tlh.getClient (), header, false, scl);
	    hhs.sendHeader ();
	} catch (IOException e) {
	    logger.log (Level.WARNING, "IOException when sending header", e);
	    closeDown ();
	}
    }

    public void logAndClose (RequestHandler rh) {
	if (rh != null && rh.getWebConnection () != null) {
	    proxy.releaseWebConnection (rh.getWebConnection ());
	}
	logConnection ();
	closeDown ();
    }

    public void logAndRestart () {
	logConnection ();
	if (getKeepalive ())
	    readRequest ();
	else
	    closeDown ();
    }

    private class SendAndCloseListener implements HttpHeaderSentListener {
	public void httpHeaderSent () {
	    status = "Response sent, logging and closing.";
	    logAndClose (null);
	}

	public void timeout () {
	    status = "Response sending timed out, logging and closing.";
	    logger.info ("Timeout when sending http header");
	    logAndClose (null);
	}

	public void failed (Exception e) {
	    status = 
		"Response sending failed: " + e + ", logging and closing.";
	    logger.log (Level.INFO, "Exception when sending http header", e);
	    logAndClose (null);
	}
    }

    protected boolean isWeak (String t) {
	return t.startsWith ("W/");
    }

    protected boolean checkStrongEtag (String et, String im) {
	return !isWeak (im) && im.equals (et);
    }

    /* Remove any W/ prefix then check if etags are equal.
     * Inputs can be in any order.
     * @return true if the etags match or at least one of the etag
     *              headers do not exist.
     */
    private boolean checkWeakEtag (HttpHeader h1, HttpHeader h2) {
	String et1 = h1.getHeader ("Etag");
	String et2 = h2.getHeader ("Etag");
	if (et1 == null || et2 == null)
	    return true;
	return checkWeakEtag (et1, et2);
    }

    /* Remove any W/ prefix from the inputs then check if they are equal.
     * Inputs can be in any order.
     * Returns true if equal.
     */
    protected boolean checkWeakEtag (String et, String im) {
	if (et == null || im == null)
	    return false;
	if (isWeak (et))
	    et = et.substring (2);
	if (isWeak (im))
	    im = im.substring (2);
	return im.equals (et);
    }

    public HttpGenerator getHttpGenerator () {
	return responseHandler;
    }

    private void logConnection () {
	getConnectionLogger ().logConnection (Connection.this);
	proxy.updateTrafficLog (tlh);
	tlh.clear ();
    }
}