package rabbit.proxy;

import rabbit.util.SProperties;

/** A HttpGeneratorFactory that creates StandardResponseHeaders 
 *  instances.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class StandardHttpGeneratorFactory implements HttpGeneratorFactory {
    @Override
    public HttpGenerator create(final String identity, final Connection con) {
        return new StandardResponseHeaders(identity, con);
    }

    @Override
    public void setup(final SProperties props) {
        // nothing to do
    }
}
