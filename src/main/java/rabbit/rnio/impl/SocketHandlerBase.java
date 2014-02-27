package rabbit.rnio.impl;

import java.nio.channels.SelectableChannel;
import java.util.logging.Logger;
import rabbit.rnio.NioHandler;
import rabbit.rnio.SocketChannelHandler;

/** A socket handler that never times out and always runs on the 
 *  selector thread.
 *
 * @param <T> the type of chanel that is handled
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public abstract class SocketHandlerBase<T extends SelectableChannel>
        implements SocketChannelHandler {
    /** The actual channel */
    public final T sc;
    /** The NioHandler used to wait for opeations. */
    public final NioHandler nioHandler;
    /** The timeout for the current operation */
    public final Long timeout;

    private static final Logger logger = Logger.getLogger("rabbit.rnio");

    /**
     * @param sc the channel to handle
     * @param nioHandler the NioHandler
     * @param timeout the timeout in millis, may be null if no timeout
     *        is wanted.
     */
    public SocketHandlerBase(final T sc, final NioHandler nioHandler, final Long timeout) {
        this.sc = sc;
        this.nioHandler = nioHandler;
        this.timeout = timeout;
    }

    /** Will return null to indicate no timeout on accepts.
     */
    @Override
    public Long getTimeout() {
        return timeout;
    }

    /** Returns the class name.
     */
    @Override
    public String getDescription() {
        return getClass().getSimpleName();
    }

    /** Will always run on the selector thread so return false.
     * @return false
     */
    @Override
    public boolean useSeparateThread() {
        return false;
    }

    /** Handle timeouts. Default implementation just calls closed().
     */
    @Override
    public void timeout() {
        closed();
    }

    @Override
    public void closed() {
        Closer.close(sc, logger);
    }
}
