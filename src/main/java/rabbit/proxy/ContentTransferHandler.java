package rabbit.proxy;

import java.nio.ByteBuffer;
import rabbit.http.HttpHeader;
import rabbit.httpio.BlockSender;
import rabbit.httpio.BlockSentListener;
import rabbit.io.BufferHandle;
import rabbit.io.SimpleBufferHandle;

/** A handler that transfers request resources with a known content length.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
class ContentTransferHandler extends ResourceHandlerBase
        implements BlockSentListener {
    private final long dataSize;
    private long transferred = 0;
    private long toTransfer = 0;

    public ContentTransferHandler (final Connection con,
                                   final BufferHandle bufHandle,
                                   final long dataSize,
                                   final TrafficLoggerHandler tlh) {
        super (con, bufHandle, tlh);
        this.dataSize = dataSize;
    }

    @Override protected void doTransfer () {
        if (transferred >= dataSize) {
            listener.clientResourceTransferred ();
            return;
        }
        super.doTransfer ();
    }

    public void modifyRequest (final HttpHeader header) {
        // nothing.
    }

    @Override void sendBuffer () {
        final ByteBuffer buffer = bufHandle.getBuffer ();
        toTransfer = Math.min (buffer.remaining (),
                               dataSize - transferred);
        BufferHandle sbufHandle = bufHandle;
        if (toTransfer < buffer.remaining ()) {
            final int limit = buffer.limit ();
            // int cast is safe since buffer.remaining returns an int
            buffer.limit (buffer.position () + (int)toTransfer);
            final ByteBuffer sendBuffer = buffer.slice ();
            buffer.limit (limit);
            sbufHandle = new SimpleBufferHandle (sendBuffer);
        }
        fireResouceDataRead (sbufHandle);
        final BlockSender bs =
                new BlockSender (wc.getChannel (), con.getNioHandler (),
                                 tlh.getNetwork (), sbufHandle, false, this);
        bs.write ();
    }

    public void blockSent () {
        transferred += toTransfer;
        if (transferred < dataSize)
            doTransfer ();
        else
            listener.clientResourceTransferred ();
    }
}
