package rabbit.httpio;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.logging.Logger;
import rabbit.rnio.NioHandler;
import rabbit.io.ProxyChain;
import rabbit.io.ProxyChainFactory;
import rabbit.util.SProperties;

/** A factory that creates InOutProxyChain:s. 
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class InOutProxyChainFactory implements ProxyChainFactory {
    @Override
    public ProxyChain getProxyChain (final SProperties props,
                                     final NioHandler nio,
                                     final Logger logger) {
        final String insideMatch = props.getProperty ("inside_match");
        final String pname = props.getProperty ("proxyhost", "").trim ();
        final String pport = props.getProperty ("proxyport", "").trim ();
        final String pauth = props.getProperty ("proxyauth");

        try {
            final InetAddress proxy = InetAddress.getByName(pname);
            try {
                final int port = Integer.parseInt (pport);
                return new InOutProxyChain (insideMatch, nio,
                                            proxy, port, pauth);
            } catch (NumberFormatException e) {
                logger.severe ("Strange proxyport: '" + pport +
                               "', will not chain");
            }
        } catch (UnknownHostException e) {
            logger.severe ("Unknown proxyhost: '" + pname +
                           "', will not chain");
        }
        return null;
    }
}
