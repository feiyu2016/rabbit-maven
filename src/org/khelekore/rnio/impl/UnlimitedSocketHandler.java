package org.khelekore.rnio.impl;

import java.nio.channels.SelectableChannel;
import java.util.logging.Logger;
import org.khelekore.rnio.NioHandler;
import org.khelekore.rnio.SocketChannelHandler;

/** A socket handler that never times out and always runs on the 
 *  selector thread.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public abstract class UnlimitedSocketHandler<T extends SelectableChannel>
    implements SocketChannelHandler {
    public final T sc;
    public final NioHandler nioHandler;

    private final Logger logger = Logger.getLogger ("org.khelekore.rnio");

    public UnlimitedSocketHandler (T sc, NioHandler nioHandler) {
	this.sc = sc;
	this.nioHandler = nioHandler;
    }
    
    /** Will return null to indicate no timeout on accepts.
     */
    public Long getTimeout () {
	return null;
    }
    
    /** Returns the class name.
     */
    public String getDescription () {
	return getClass ().getSimpleName ();
    }

    /** Will always run on the selector thread so return false.
     * @return false
     */
    public boolean useSeparateThread () {
	return false;
    }

    /** Handle timeouts. We should not get any timeout.
     */
    public void timeout () {
	throw new IllegalStateException ("Should not get timeout");
    }

    public void closed () {
	Closer.close (sc, logger);
    }
}
