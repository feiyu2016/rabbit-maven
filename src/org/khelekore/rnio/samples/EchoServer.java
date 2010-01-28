package org.khelekore.rnio.samples;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import org.khelekore.rnio.BufferHandler;
import org.khelekore.rnio.NioHandler;
import org.khelekore.rnio.ReadHandler;
import org.khelekore.rnio.WriteHandler;
import org.khelekore.rnio.impl.AcceptingServer;
import org.khelekore.rnio.impl.AcceptorListener;
import org.khelekore.rnio.impl.CachingBufferHandler;
import org.khelekore.rnio.impl.Closer;
import org.khelekore.rnio.impl.UnlimitedSocketHandler;

/** An echo server built using rnio. This echo server will handle
 *  many concurrent clients without any problems.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class EchoServer {
    private final AcceptingServer as;
    private final BufferHandler bufferHandler;
    private final AcceptListener acceptHandler;
    private final Logger logger =
	Logger.getLogger ("org.khelekore.rnio.echoserver");

    private final ByteBuffer QUIT =
	ByteBuffer.wrap ("quit\r\n".getBytes ("UTF-8"));

    public static void main (String[] args) {
	int port = 9999;

	if (args.length > 0)
	    port = Integer.parseInt (args[0]);

	try {
	    EchoServer es = new EchoServer (port);
	    es.start ();
	} catch (IOException e) {
	    e.printStackTrace ();
	}
    }

    public EchoServer (int port) throws IOException {
	bufferHandler = new CachingBufferHandler ();
	acceptHandler = new AcceptListener ();
	as = new AcceptingServer (null, port, acceptHandler, 
				  Executors.newCachedThreadPool (), 1);
    }

    public void start () throws IOException {
	as.start ();
    }

    public ByteBuffer getBuffer () {
	return bufferHandler.getBuffer ();
    }

    public void returnBuffer (ByteBuffer buf) {
	bufferHandler.putBuffer (buf);
    }

    private void quit () {
	as.shutdown ();
    }

    private class AcceptListener implements AcceptorListener {
	public void connectionAccepted (SocketChannel sc) throws IOException {
	    Reader rh = new Reader (sc, as.getNioHandler ());
	    rh.register ();
	}
    }

    private class Reader extends UnlimitedSocketHandler<SocketChannel>
	implements ReadHandler {
	public Reader (SocketChannel sc, NioHandler nioHandler) {
	    super (sc, nioHandler);
	}

	public void read () {
	    try {
		ByteBuffer buf = getBuffer ();
		int read = sc.read (buf);
		if (read == -1) {
		    returnBuffer (buf);
		    closed ();
		    return;
		}
		if (read == 0) {
		    register ();
		} else {
		    buf.flip ();
		    if (quitMessage (buf)) {
			quit ();
		    } else {
			Writer writer = new Writer (sc, nioHandler, buf, this);
			writer.write ();
		    }
		}
	    } catch (IOException e) {
		e.printStackTrace ();
		Closer.close (sc, logger);
	    }
	}

	private boolean quitMessage (ByteBuffer buf) {
	    return buf.compareTo (QUIT) == 0;
	}

	public void register () {
	    nioHandler.waitForRead (sc, this);
	}
    }

    private class Writer extends UnlimitedSocketHandler<SocketChannel>
	implements WriteHandler {
	private final ByteBuffer buf;
	private final Reader reader;

	public Writer (SocketChannel sc, NioHandler nioHandler,
		       ByteBuffer buf, Reader reader) {
	    super (sc, nioHandler);
	    this.buf = buf;
	    this.reader = reader;
	}

	public void write () {
	    try {
		int written = 0;
		do {
		    written = sc.write (buf);
		} while (buf.hasRemaining () && written > 0);
		if (buf.hasRemaining ()) {
		    register ();
		} else {
		    returnBuffer (buf);
		    reader.register ();
		}
	    } catch (IOException e) {
		e.printStackTrace ();
		Closer.close (sc, logger);
	    }
	}

	public void register () {
	    nioHandler.waitForWrite (sc, this);
	}
    }
}
