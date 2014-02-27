package rabbit.io;

import java.util.logging.Logger;
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
     * @param logger the Logger to log errors to
     * @return the new ProxyChain
     */
    ProxyChain getProxyChain (SProperties props, 
			      NioHandler nio, 
			      Logger logger);
}
