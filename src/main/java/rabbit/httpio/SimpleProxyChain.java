package rabbit.httpio;

import rabbit.rnio.NioHandler;
import rabbit.io.ProxyChain;
import rabbit.io.Resolver;

/** A default implementation of a ProxyChain that always return 
 *  the same SimpleResolver.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class SimpleProxyChain implements ProxyChain {
    private final Resolver resolver;

    /** Create a new Proxy chain that always uses direct connections.
     * @param nio the NioHandler to use for running background tasks
     */
    public SimpleProxyChain (final NioHandler nio) {
        resolver = new SimpleResolver (nio);
    }

    @Override
    public Resolver getResolver (final String url) {
        return resolver;
    }
}
