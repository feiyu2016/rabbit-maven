package rabbit.rnio.impl;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import rabbit.rnio.NioHandler;
import rabbit.rnio.WriteHandler;

/** A simple sender of data. Will try to send all data with no timeout.
 *
 * <p>Subclass this sender and implement <code>done()</code> to do any 
 * work after the data has been sent.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
@Slf4j
public abstract class SimpleBlockSender
        extends SocketHandlerBase<SocketChannel>
        implements WriteHandler {
    private final ByteBuffer buf;

    /**
     * @param sc the channel to handle
     * @param nioHandler the NioHandler
     * @param buf the ByteBuffer to send
     * @param timeout the timeout in millis, may be null if no timeout
     *        is wanted.
     */
    public SimpleBlockSender(final SocketChannel sc,
                             final NioHandler nioHandler,
                             final ByteBuffer buf,
                             final Long timeout) {
        super(sc, nioHandler, timeout);
        this.buf = buf;
    }

    /** Get the buffer we are sending data from.
     * @return the ByteBuffer with the data that is being sent
     */
    public ByteBuffer getBuffer() {
        return buf;
    }

    @Override
    public void write() {
        try {
            int written;
            do {
                written = sc.write(buf);
            } while (buf.hasRemaining() && written > 0);
            if (buf.hasRemaining()) {
                register();
            } else {
                done();
            }
        } catch (IOException e) {
            handleIOException(e);
        }
    }

    /** Handle the exception, default is to log it and to close the channel. 
     * @param e the IOException that is the cause of data write failure
     */
    private void handleIOException(final IOException e) {
        log.warn("Failed to send data", e);
        Closer.close(sc);
    }

    /** The default is to do nothing, override in subclasses if needed.
     */
    private void done() {
        // empty
    }

    /** Register writeWait on the nioHandler 
     */
    private void register() {
        nioHandler.waitForWrite(sc, this);
    }
}
