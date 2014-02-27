package rabbit.io;

import java.net.InetAddress;

/** A binder that only binds to the wildcard address and port.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class DefaultBinder implements SocketBinder {
    @Override
    public int getPort() {
        return 0;
    }

    @Override
    public InetAddress getInetAddress() {
        return null;
    }
}
