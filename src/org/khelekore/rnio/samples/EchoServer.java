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
import org.khelekore.rnio.NioHandler;
import org.khelekore.rnio.ReadHandler;
import org.khelekore.rnio.StatisticsHolder;
import org.khelekore.rnio.WriteHandler;
import org.khelekore.rnio.impl.Acceptor;
import org.khelekore.rnio.impl.AcceptorListener;
import org.khelekore.rnio.impl.BasicStatisticsHolder;
import org.khelekore.rnio.impl.Closer;
import org.khelekore.rnio.impl.MultiSelectorNioHandler;

/** An echo server built using rnio.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class EchoServer {
    private final NioHandler nioHandler;
    private final ServerSocketChannel ssc;
    private final AcceptListener acceptHandler;
    private final Logger logger = Logger.getLogger ("org.khelekore.rnio.echoserver");

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
	return ByteBuffer.allocate (1024);
    }

    public void returnBuffer (ByteBuffer buf) {
	// nothing.
    }
    
    private class AcceptListener implements AcceptorListener {
	public void connectionAccepted (SocketChannel sc) throws IOException {
	    Reader rh = new Reader (sc);
	    rh.register ();
	}
    }

    private abstract class SockHandler {
	public final SocketChannel sc;

	public SockHandler (SocketChannel sc) {
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
	    System.out.println ("sc: " + sc + " has been closed");
	    Closer.close (sc, logger);
	}
    }

    private class Reader extends SockHandler implements ReadHandler {
	public Reader (SocketChannel sc) {
	    super (sc);
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
		    Writer writer = new Writer (sc, buf, this);
		    writer.write ();
		}
	    } catch (IOException e) {
		e.printStackTrace ();
		Closer.close (sc, logger);
	    }
	}

	public void register () {
	    nioHandler.waitForRead (sc, this);
	}
    }

    private class Writer extends SockHandler implements WriteHandler {
	private final ByteBuffer buf;
	private final Reader reader;

	public Writer (SocketChannel sc, ByteBuffer buf, Reader reader) {
	    super (sc);
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
