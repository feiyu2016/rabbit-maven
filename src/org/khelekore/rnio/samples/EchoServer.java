package org.khelekore.rnio.samples;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import org.khelekore.rnio.BufferHandler;
import org.khelekore.rnio.NioHandler;
import org.khelekore.rnio.ReadHandler;
import org.khelekore.rnio.StatisticsHolder;
import org.khelekore.rnio.WriteHandler;
import org.khelekore.rnio.impl.Acceptor;
import org.khelekore.rnio.impl.AcceptorListener;
import org.khelekore.rnio.impl.BasicStatisticsHolder;
import org.khelekore.rnio.impl.CachingBufferHandler;
import org.khelekore.rnio.impl.Closer;
import org.khelekore.rnio.impl.MultiSelectorNioHandler;
import org.khelekore.rnio.impl.UnlimitedSocketHandler;

/** An echo server built using rnio. This echo server will handle
 *  many concurrent clients without any problems.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class EchoServer {
    private final NioHandler nioHandler;
    private final ServerSocketChannel ssc;
    private final BufferHandler bufferHandler;
    private final AcceptListener acceptHandler;
    private final Logger logger =
	Logger.getLogger ("org.khelekore.rnio.echoserver");

    private final ByteBuffer QUIT =
	ByteBuffer.wrap ("quit\r\n".getBytes ("UTF-8"));

    public static void main (String[] args) {
	int port = 9007;

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
	ssc = ServerSocketChannel.open ();
	ssc.configureBlocking (false);
	ServerSocket ss = ssc.socket ();
	ss.bind (new InetSocketAddress (port));
	bufferHandler = new CachingBufferHandler ();

	ExecutorService es = Executors.newCachedThreadPool ();
	StatisticsHolder stats = new BasicStatisticsHolder ();
	nioHandler = new MultiSelectorNioHandler (es, stats, 1);
	acceptHandler = new AcceptListener ();
    }

    public void start () throws IOException {
	nioHandler.start ();
	Acceptor acceptor = new Acceptor (ssc, nioHandler, acceptHandler);
	acceptor.register ();
    }

    public ByteBuffer getBuffer () {
	return bufferHandler.getBuffer ();
    }

    public void returnBuffer (ByteBuffer buf) {
	bufferHandler.putBuffer (buf);
    }

    private void quit () {
	nioHandler.shutdown ();
    }

    private class AcceptListener implements AcceptorListener {
	public void connectionAccepted (SocketChannel sc) throws IOException {
	    Reader rh = new Reader (sc, nioHandler);
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
