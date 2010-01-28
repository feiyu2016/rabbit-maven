package org.khelekore.rnio.impl;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.ExecutorService;
import org.khelekore.rnio.NioHandler;
import org.khelekore.rnio.StatisticsHolder;
import org.khelekore.rnio.impl.AcceptorListener;
import org.khelekore.rnio.impl.BasicStatisticsHolder;
import org.khelekore.rnio.impl.MultiSelectorNioHandler;

/** A basic server for rnio.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class AcceptingServer {
    private final ServerSocketChannel ssc;
    private final AcceptorListener listener;
    private final NioHandler nioHandler;

    /** Create a new server using the parameters given.
     * @param addr the InetAddress to bind to, may be null for wildcard address
     * @param port the port number to bind to
     * @param listener the client that will handle the accepted sockets
     * @param es the ExecutorService to use for the NioHandler
     * @param selectorThreads the number of threads that the NioHandler will use
     */
    public AcceptingServer (InetAddress addr, int port, 
			    AcceptorListener listener, 
			    ExecutorService es, 
			    int selectorThreads) 
	throws IOException {
	ssc = ServerSocketChannel.open ();
	ssc.configureBlocking (false);
	ServerSocket ss = ssc.socket ();
	ss.bind (new InetSocketAddress (addr, port));
	this.listener = listener;
	StatisticsHolder stats = new BasicStatisticsHolder ();
	nioHandler = new MultiSelectorNioHandler (es, stats, selectorThreads);
    }

    /** Start the NioHandler and register to accept new socket connections.
     */
    public void start () throws IOException {
	nioHandler.start ();
	Acceptor acceptor = new Acceptor (ssc, nioHandler, listener);
	acceptor.register ();
    }

    /** Shutdown the NioHandler.
     */
    public void shutdown () {
	nioHandler.shutdown ();	
    }

    /** Get the NioHandler in use by this server.
     */
    public NioHandler getNioHandler () {
	return nioHandler;
    }
}
