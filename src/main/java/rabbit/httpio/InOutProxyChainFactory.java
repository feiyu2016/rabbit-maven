package rabbit.httpio;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.UnknownHostException;

import rabbit.rnio.NioHandler;
import rabbit.io.ProxyChain;
import rabbit.io.ProxyChainFactory;
import rabbit.util.SProperties;

/** A factory that creates InOutProxyChain:s. 
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
@Slf4j
public class InOutProxyChainFactory implements ProxyChainFactory {
    @Override
    public ProxyChain getProxyChain(final SProperties props,
                                    final NioHandler nio) {
        final String insideMatch = props.getProperty("inside_match");
        final String pname = props.getProperty("proxyhost", "").trim();
        final String pport = props.getProperty("proxyport", "").trim();
        final String pauth = props.getProperty("proxyauth");

        try {
            final InetAddress proxy = InetAddress.getByName(pname);
            try {
                final int port = Integer.parseInt(pport);
                return new InOutProxyChain(insideMatch, nio,
                                           proxy, port, pauth);
            } catch (NumberFormatException e) {
                log.error("Strange proxyport: '{}', will not chain", pport);
            }
        } catch (UnknownHostException e) {
            log.error("Unknown proxyhost: '{}', will not chain", pname);
        }
        return null;
    }
}
