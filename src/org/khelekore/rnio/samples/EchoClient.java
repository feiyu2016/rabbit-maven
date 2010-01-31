package org.khelekore.rnio.samples;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.khelekore.rnio.NioHandler;
import org.khelekore.rnio.ReadHandler;
import org.khelekore.rnio.StatisticsHolder;
import org.khelekore.rnio.impl.BasicStatisticsHolder;
import org.khelekore.rnio.impl.Closer;
import org.khelekore.rnio.impl.MultiSelectorNioHandler;
import org.khelekore.rnio.impl.SimpleBlockSender;
import org.khelekore.rnio.impl.UnlimitedSocketHandler;

/** An echo client built using rnio. 
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class EchoClient {
    private final BufferedReader input;
    private final PrintWriter output;
    private final SocketChannel serverChannel;
    private final NioHandler nioHandler;
    private final Thread inputReaderThread;
    private final Logger logger =
	Logger.getLogger ("org.khelekore.rnio.echoserver");

    public EchoClient (String host, int port, 
		       BufferedReader input, 
		       PrintWriter output) 
	throws IOException {
	this.input = input;
	this.output = output;

	serverChannel = 
	    SocketChannel.open (new InetSocketAddress (host, port));
	serverChannel.configureBlocking (false);

	inputReaderThread = new Thread (new InputReader ());

	ExecutorService es = Executors.newCachedThreadPool ();
	StatisticsHolder stats = new BasicStatisticsHolder ();	
	nioHandler = new MultiSelectorNioHandler (es, stats, 1);
    }

    public void start () throws IOException {
	nioHandler.start ();
	ServerReader sr = new ServerReader (nioHandler);
	nioHandler.waitForRead (serverChannel, sr);
	inputReaderThread.start ();
    }

    public void shutdown () {
	nioHandler.shutdown ();
	Closer.close (serverChannel, logger);
	// would want to shutdown inputReaderThread but it will be
	// blocked in BufferedReader.readLine and that one is not
	// inerruptible.
    }

    private class ServerReader extends UnlimitedSocketHandler<SocketChannel>
	implements ReadHandler {
	public ServerReader (NioHandler nioHandler) {
	    super (serverChannel, nioHandler);
	}
	public void read () {
	    ByteBuffer buf = ByteBuffer.allocate (1024);
	    try {
		int read = sc.read (buf);
		if (read == -1) {
		    logger.info ("Server shut down");
		    shutdown ();
		    return;
		}
		buf.flip ();
		String s = new String (buf.array (), 
				       buf.position (), 
				       buf.remaining (), 
				       "UTF-8");
		output.println ("Server sent: " + s);
		output.flush ();
		nioHandler.waitForRead (serverChannel, this);
	    } catch (IOException e) {
		logger.log (Level.WARNING, "Failed to read", e);
	    }
	}
    }

    private class Sender extends SimpleBlockSender {
	public Sender (NioHandler nioHandler, ByteBuffer buf) {
	    super (serverChannel, nioHandler, buf);
	}
    }

    private class InputReader implements Runnable {
	public void run () {
	    try {
		while (true) {
		    String line = input.readLine ();
		    if (line == null || !serverChannel.isOpen ())
			return;
		    byte[] bytes = line.getBytes ("UTF-8");
		    ByteBuffer buf = ByteBuffer.wrap (bytes);
		    Sender s = new Sender (nioHandler, buf);
		    // if we fail to send everything before we read the next
		    // line we may end up with several writers, but this is
		    // an example, handle concurrency in real apps.
		    s.write ();
		}
	    } catch (IOException e) {
		logger.log (Level.WARNING, "Failed to read", e);
	    } finally {
		shutdown ();
	    }
	}
    }

    public static void main (String[] args) {
	if (args.length < 2) {
	    usage ();
	    return;
	}
	String host = args[0];
	int port = Integer.parseInt (args[1]);
	InputStreamReader isr = new InputStreamReader (System.in);
	BufferedReader br = new BufferedReader (isr);
	PrintWriter pw = new PrintWriter (System.out);
	try {
	    EchoClient ec = new EchoClient (host, port, br, pw);
	    ec.start ();
	} catch (IOException e) {
	    e.printStackTrace ();
	}
    }

    private static void usage () {
	System.err.println ("java " + EchoClient.class.getName () +
			    " <host> <port>");
    }
}
