package org.khelekore.rnio.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.khelekore.rnio.NioHandler;
import org.khelekore.rnio.ReadHandler;

/** A reader of data. Will wait until a channel is read-ready and then
 *  read a block of data from it.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public abstract class SimpleBlockReader
    extends UnlimitedSocketHandler<SocketChannel>
    implements ReadHandler {

    private final Logger logger =
	Logger.getLogger ("org.khelekore.rnio");

    /** Create a new block reader.
     * @param sc the channel to read from
     * @param nioHandler the NioHandler to use for waiting on data
     */
    public SimpleBlockReader (SocketChannel sc, NioHandler nioHandler) {
	super (sc, nioHandler);
    }

    /** Try to read data from the channel.
     */
    public void read () {
	try {
	    ByteBuffer buf = getByteBuffer ();
	    int read = sc.read (buf);
	    if (read == -1) {
		channelClosed (sc);
		putByteBuffer (buf);
		return;
	    }
	    if (read == 0) {
		putByteBuffer (buf);
		register ();
	    } else {
		buf.flip ();
		handleBufferRead (sc, buf);
	    }
	} catch (IOException e) {
	    handleIOException (e);
	}
    }

    /** Called before a read attempt is made.
     *	The default is to create a new 1kB big ByteBuffer and return it.
     */
    public ByteBuffer getByteBuffer () {
	return ByteBuffer.allocate (1024);
    }

    /** Return the ByteBuffer, this method will be called when read gets EOF
     *  or no data. The default is to do nothing.
     */
    public void putByteBuffer (ByteBuffer buf) {
	// nothing.
    }

    /** Handle the exception, default is to log it and to close the channel.
     */
    public void handleIOException (IOException e) {
	logger.log (Level.WARNING, "Failed to read data", e);
	Closer.close (sc, logger);
    }

    /** Do any cleanup that needs to be done when the channel we tried
     *  to read from was closed.
     */
    public abstract void channelClosed (SocketChannel sc);

    /** Handle the buffer content. */
    public abstract void handleBufferRead (SocketChannel sc, ByteBuffer buf)
	throws IOException;

    /** Wait for the channel to become read ready.*/
    public void register () {
	nioHandler.waitForRead (sc, this);
    }
}