package org.khelekore.rnio.impl;

import java.nio.channels.SocketChannel;
import java.util.logging.Logger;
import org.khelekore.rnio.SocketChannelHandler;

/** A socket handler that never times out and always runs on the 
 *  selector thread.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public abstract class UnlimitedSocketHandler implements SocketChannelHandler {
    public final SocketChannel sc;
    private final Logger logger = Logger.getLogger ("org.khelekore.rnio");

    public UnlimitedSocketHandler (SocketChannel sc) {
	this.sc = sc;
    }
    
    public Long getTimeout () {
	return null;
    }
    
    public String getDescription () {
	return getClass ().getSimpleName ();
    }

    public boolean useSeparateThread () {
	return false;
    }

    public void timeout () {
	throw new IllegalStateException ("Should not get timeout");
    }

    public void closed () {
	Closer.close (sc, logger);
    }
}
