package rabbit.proxy;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicLong;

import rabbit.rnio.BufferHandler;
import rabbit.rnio.impl.AcceptorListener;

/** An acceptor handler that creates proxy client connection
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
@Slf4j
public class ProxyConnectionAcceptor implements AcceptorListener {
    private final int id;
    private final HttpProxy proxy;
    private final AtomicLong counter = new AtomicLong();

    /** Create a new ProxyConnectionAcceptor.
     * @param id the connection group id
     * @param proxy the HttpProxy to accept connections for
     */
    public ProxyConnectionAcceptor(final int id, final HttpProxy proxy) {
        log.trace("ProxyConnectionAcceptor created: {}", id);
        this.id = id;
        this.proxy = proxy;
    }

    @Override
    public void connectionAccepted(final SocketChannel sc)
            throws IOException {
        proxy.getCounter().inc("Socket accepts");
        log.trace("Accepted connection from: {}", sc);
        final BufferHandler bh = proxy.getBufferHandler();
        final Connection c = new Connection(getId(), sc, proxy, bh);
        c.readRequest();
    }

    private ConnectionId getId() {
        return new ConnectionId(id, counter.incrementAndGet());
    }
}
