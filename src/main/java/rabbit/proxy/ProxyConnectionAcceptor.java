package rabbit.proxy;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import rabbit.rnio.BufferHandler;
import rabbit.rnio.impl.AcceptorListener;

/** An acceptor handler that creates proxy client connection
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class ProxyConnectionAcceptor implements AcceptorListener {
    private final int id;
    private final HttpProxy proxy;
    private static final Logger logger = Logger.getLogger(ProxyConnectionAcceptor.class.getName());
    private final AtomicLong counter = new AtomicLong();

    /** Create a new ProxyConnectionAcceptor.
     * @param id the connection group id
     * @param proxy the HttpProxy to accept connections for
     */
    public ProxyConnectionAcceptor(final int id, final HttpProxy proxy) {
        logger.fine("ProxyConnectionAcceptor created: " + id);
        this.id = id;
        this.proxy = proxy;
    }

    @Override
    public void connectionAccepted(final SocketChannel sc)
            throws IOException {
        proxy.getCounter().inc("Socket accepts");
        if (logger.isLoggable(Level.FINE)) {
            logger.fine("Accepted connection from: " + sc);
        }
        final BufferHandler bh = proxy.getBufferHandler();
        final Connection c = new Connection(getId(), sc, proxy, bh);
        c.readRequest();
    }

    private ConnectionId getId() {
        return new ConnectionId(id, counter.incrementAndGet());
    }
}
