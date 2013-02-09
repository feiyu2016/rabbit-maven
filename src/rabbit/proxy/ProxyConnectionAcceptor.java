package rabbit.proxy;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.khelekore.rnio.impl.AcceptorListener;
import rabbit.util.Config;

/** An acceptor handler that creates proxy client connection
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class ProxyConnectionAcceptor implements AcceptorListener {
    private final HttpProxy proxy;
    private final Logger logger = Logger.getLogger (getClass ().getName ());
    private final AtomicLong counter = new AtomicLong ();
    private final int id;
    private final boolean setTcpNoDelay;

    /** Create a new ProxyConnectionAcceptor.
     * @param id the connection group id
     * @param proxy the HttpProxy to accept connections for
     */
    public ProxyConnectionAcceptor (int id, HttpProxy proxy) {
	logger.fine ("ProxyConnectionAcceptor created: " + id);
	this.id = id;
	this.proxy = proxy;
	Config c = proxy.getConfig ();
	String tcpNoDelay = c.getProperty (HttpProxy.class.getName (),
					   "use_tcp_no_delay", "false");
	setTcpNoDelay = "true".equalsIgnoreCase (tcpNoDelay);
    }

    public void connectionAccepted (SocketChannel sc) 
	throws IOException {
	proxy.getCounter ().inc ("Socket accepts");
	if (logger.isLoggable (Level.FINE))
	    logger.fine ("Accepted connection from: " + sc);
	if (!proxy.getSocketAccessController ().checkAccess (sc)) {
	    logger.warning ("Rejecting access from " + 
			    sc.socket ().getInetAddress ());
	    proxy.getCounter ().inc ("Rejected IP:s");
	    sc.close ();
	} else {
	    if (setTcpNoDelay)
		sc.socket ().setTcpNoDelay (true);
	    Connection c = new Connection (getId (), sc, proxy);
	    c.readRequest ();
	}
    }

    private ConnectionId getId () {
	return new ConnectionId (id, counter.incrementAndGet ());
    }
}
