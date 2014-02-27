package rabbit.proxy;

import rabbit.http.HttpHeader;
import rabbit.httpio.BlockListener;
import rabbit.httpio.BlockSender;
import rabbit.httpio.BlockSentListener;
import rabbit.httpio.ChunkDataFeeder;
import rabbit.httpio.ChunkEnder;
import rabbit.httpio.ChunkHandler;
import rabbit.io.BufferHandle;

/** A handler that transfers chunked request resources.
 *  Will chunk data to the real server or fail. Note that we can only
 *  do this if we know that the upstream server is HTTP/1.1 compatible.
 *
 *  How do we determine if upstream is HTTP/1.1 compatible?
 *  If we can not then we have to add a Content-Length header and not chunk,
 *  That means we have to buffer the full resource.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
class ChunkedContentTransferHandler extends ResourceHandlerBase
        implements ChunkDataFeeder, BlockListener, BlockSentListener {

    private boolean sentEndChunk = false;
    private final ChunkHandler chunkHandler;

    public ChunkedContentTransferHandler (final Connection con,
                                          final BufferHandle bufHandle,
                                          final TrafficLoggerHandler tlh) {
        super (con, bufHandle, tlh);
        chunkHandler = new ChunkHandler (this, con.getProxy ().getStrictHttp ());
        chunkHandler.setBlockListener (this);
    }

    @Override
    public void modifyRequest (final HttpHeader header) {
        header.setHeader ("Transfer-Encoding", "chunked");
    }

    @Override void sendBuffer () {
        chunkHandler.handleData (bufHandle);
    }

    @Override
    public void bufferRead (final BufferHandle bufHandle) {
        fireResouceDataRead (bufHandle);
        final BlockSender bs =
                new BlockSender (wc.getChannel (), con.getNioHandler (),
                                 tlh.getNetwork (), bufHandle, true, this);
        bs.write ();
    }

    @Override
    public void finishedRead () {
        final ChunkEnder ce = new ChunkEnder ();
        sentEndChunk = true;
        ce.sendChunkEnding (wc.getChannel (), con.getNioHandler (),
                            tlh.getNetwork (), this);
    }

    @Override
    public void register () {
        waitForRead ();
    }

    @Override
    public void readMore () {
        if (!bufHandle.isEmpty ()) {
            bufHandle.getBuffer().compact();
        }
        register ();
    }

    @Override
    public void blockSent () {
        if (sentEndChunk) {
            listener.clientResourceTransferred();
        } else {
            doTransfer();
        }
    }
}
