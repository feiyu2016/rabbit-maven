package rabbit.httpio;

import java.io.IOException;
import java.nio.channels.SocketChannel;

import rabbit.rnio.BufferHandler;
import rabbit.rnio.NioHandler;
import rabbit.http.HttpHeader;
import rabbit.io.BufferHandle;
import rabbit.io.CacheBufferHandle;
import rabbit.util.TrafficLogger;

/** A handler that write one http header and reads a response
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class HttpResponseReader 
    implements HttpHeaderSentListener, HttpHeaderListener {

    private final SocketChannel channel;
    private final NioHandler nioHandler;
    private final TrafficLogger tl;
    private final BufferHandler bufHandler;
    private final boolean strictHttp;
    private final HttpResponseListener listener;
    private final HttpHeaderSender sender;

    /** Create a new HttpResponseReader.
     * @param channel the Channel to the client
     * @param nioHandler the NioHandler to use for network and background tasks
     * @param tl the network statistics gatherer
     * @param bufHandler the BufferHandler to use
     * @param header the request to send
     * @param fullURI if true the request will have a full uri instead of just
     *        a relative one
     * @param strictHttp if true then use strict http
     * @param listener the listener that will be notified when the response has
     *        been read.
     * @throws IOException if the request can not be sent
     */
    public HttpResponseReader (final SocketChannel channel, final NioHandler nioHandler, 
			       final TrafficLogger tl, final BufferHandler bufHandler, 
			       final HttpHeader header, final boolean fullURI, 
			       final boolean strictHttp, 
			       final HttpResponseListener listener)
	throws IOException {
	this.channel = channel;
	this.nioHandler = nioHandler;
	this.tl = tl;
	this.bufHandler = bufHandler;
	this.strictHttp = strictHttp;
	this.listener = listener;
	sender = new HttpHeaderSender (channel, nioHandler, tl, 
				       header, fullURI, this);
    }

    /** Start the process of sending the header and reading the response.
     */
    public void sendRequestAndWaitForResponse () {
	sender.sendHeader ();
    }
    
    public void httpHeaderSent () {
	try {
	    final BufferHandle bh = new CacheBufferHandle (bufHandler);
	    final HttpHeaderReader reader = 
		new HttpHeaderReader (channel, bh, nioHandler,
				      tl, false, strictHttp, this);
	    reader.readHeader ();
	} catch (IOException e) {
	    failed (e);
	}
    }
    
    public void httpHeaderRead (final HttpHeader header, final BufferHandle bh, 
				final boolean keepalive, final boolean isChunked, 
				final long dataSize) {
	listener.httpResponse (header, bh, keepalive, isChunked, dataSize);
    }
    
    public void closed () {
	listener.failed (new IOException ("Connection closed"));
    }
    
    public void failed (final Exception cause) {
	listener.failed (cause);
    }

    public void timeout () {
	listener.timeout ();
    }
}
