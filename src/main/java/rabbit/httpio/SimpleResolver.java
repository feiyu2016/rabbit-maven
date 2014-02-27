package rabbit.httpio;

import java.net.URL;
import rabbit.rnio.NioHandler;
import rabbit.rnio.impl.DefaultTaskIdentifier;
import rabbit.io.InetAddressListener;
import rabbit.io.Resolver;

/** A simple resolver that uses the given dns handler.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class SimpleResolver implements Resolver {
    private final NioHandler nio;

    /** Create a new Resolver that does normal DNS lookups.
     * @param nio the NioHandler to use for running background tasks
     */
    public SimpleResolver (final NioHandler nio) {
        this.nio = nio;
    }

    @Override
    public void getInetAddress (final URL url, final InetAddressListener listener) {
        final String groupId = getClass ().getSimpleName ();
        nio.runThreadTask (new ResolvRunner (url, listener),
                           new DefaultTaskIdentifier (groupId, url.toString ()));
    }

    @Override
    public int getConnectPort (final int port) {
        return port;
    }

    @Override
    public boolean isProxyConnected () {
        return false;
    }

    @Override
    public String getProxyAuthString () {
        return null;
    }
}
