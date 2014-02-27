package rabbit.httpio;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import rabbit.rnio.NioHandler;
import rabbit.io.BufferHandle;
import rabbit.io.SimpleBufferHandle;
import rabbit.rnio.WriteHandler;
import rabbit.util.TrafficLogger;

/** A class that sends the chunk ending (with an empty footer).
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class ChunkEnder {
    private static final byte[] CHUNK_ENDING =
            new byte[] {'0', '\r', '\n', '\r', '\n'};

    /** Send the chunk ending block.
     * @param channel the Channel to send the chunk ender to
     * @param nioHandler the NioHandler to use for network operations
     * @param tl the TrafficLogger to update with network statistics
     * @param bsl the listener that will be notified when the sending is
     *        complete
     */
    public void sendChunkEnding(final SocketChannel channel, final NioHandler nioHandler,
                                final TrafficLogger tl, final BlockSentListener bsl) {
        final ByteBuffer bb = ByteBuffer.wrap(CHUNK_ENDING);
        final BufferHandle bh = new SimpleBufferHandle(bb);
        final WriteHandler bs =
                new BlockSender(channel, nioHandler, tl, bh, false, bsl);
        bs.write();
    }
}
