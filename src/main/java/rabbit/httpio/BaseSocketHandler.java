package rabbit.httpio;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.logging.Logger;
import rabbit.rnio.NioHandler;
import rabbit.rnio.ReadHandler;
import rabbit.rnio.SocketChannelHandler;
import rabbit.rnio.WriteHandler;
import rabbit.io.BufferHandle;

/** A base class for socket handlers.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public abstract class BaseSocketHandler implements SocketChannelHandler {
    /** The client channel. */
    private final SocketChannel channel;

    /** The nio handler we are using. */
    private final NioHandler nioHandler;

    /** The logger to use. */
    static final Logger logger = Logger.getLogger(BaseSocketHandler.class.getName());

    /** The buffer handle. */
    private final BufferHandle bh;

    /** The timeout value set by the previous channel registration */
    private Long timeout;

    /** Create a new BaseSocketHandler that will handle the traffic on 
     *  the given channel
     * @param channel the SocketChannel to read to and write from
     * @param bh the BufferHandle to use for the io operation
     * @param nioHandler the NioHandler to use to wait for operations on
     */
    BaseSocketHandler(final SocketChannel channel, final BufferHandle bh,
                      final NioHandler nioHandler) {
        this.channel = channel;
        this.bh = bh;
        this.nioHandler = nioHandler;
    }

    ByteBuffer getBuffer() {
        return bh.getBuffer();
    }

    void releaseBuffer() {
        bh.possiblyFlush();
    }

    /** Does nothing by default */
    @Override
    public void closed() {
        // empty
    }

    /** Does nothing by default */
    @Override
    public void timeout() {
        // empty
    }

    /** Runs on the selector thread by default */
    @Override
    public boolean useSeparateThread() {
        return false;
    }

    @Override
    public String getDescription() {
        return getClass().getName() + ":" + channel;
    }

    @Override
    public Long getTimeout() {
        return timeout;
    }

    Logger getLogger() {
        return     logger;
    }

    void closeDown() {
        releaseBuffer();
        nioHandler.close(channel);
    }

    /** Get the channel this BaseSocketHandler is using
     * @return the SocketChannel being used 
     */
    SocketChannel getChannel() {
        return channel;
    }

    /** Get the BufferHandle this BaseSocketHandler is using
     * @return the BufferHandle used for io operations
     */
    BufferHandle getBufferHandle() {
        return bh;
    }

    /** Wait for more data to be readable on the channel
     * @param rh the handler that will be notified when more data is
     *        ready to be read
     */
    void waitForRead(final ReadHandler rh) {
        this.timeout = nioHandler.getDefaultTimeout();
        nioHandler.waitForRead(channel, rh);
    }

    /** Wait for more data to be writable on the channel
     * @param rh the handler that will be notified when more data is
     *        ready to be written
     */
    void waitForWrite(final WriteHandler rh) {
        this.timeout = nioHandler.getDefaultTimeout();
        nioHandler.waitForWrite(channel, rh);
    }
}
