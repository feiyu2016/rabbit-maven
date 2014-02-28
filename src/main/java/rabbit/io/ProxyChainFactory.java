package rabbit.io;

import rabbit.rnio.NioHandler;
import rabbit.util.SProperties;

/** A constructor of ProxyChain:s.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public interface ProxyChainFactory {
    /** Create a ProxyChain given the properties. 
     * @param props the properties to use when constructing the proxy chain
     * @param nio the NioHandler to use for network and background tasks
     * @return the new ProxyChain
     */
    ProxyChain getProxyChain(SProperties props, NioHandler nio);
}
