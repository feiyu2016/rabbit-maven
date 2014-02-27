package rabbit.httpio;

import java.net.InetAddress;
import rabbit.io.ProxyChain;
import rabbit.io.Resolver;

/** An implementation of ProxyChain that always goes through some 
 *  other proxy
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class ProxiedProxyChain implements ProxyChain {
    private final Resolver resolver;

    /** Create a new ProxyChain that always will proxy to the given address
     * @param proxy the hostname to connect to
     * @param port the port to connect to
     * @param proxyAuth the http basic proxy authentication token
     */
    public ProxiedProxyChain (final InetAddress proxy, final int port, final String proxyAuth) {
        resolver = new ProxyResolver (proxy, port, proxyAuth);
    }

    @Override
    public Resolver getResolver (final String url) {
        return resolver;
    }
}
