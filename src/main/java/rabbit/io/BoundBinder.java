package rabbit.io;

import java.net.InetAddress;

/** A binder that will bind to a specific InetAddress
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class BoundBinder implements SocketBinder {
    private final InetAddress ia;

    /** Create a new SocketBinder that will always bind to the given
     *  InetAddress.
     * @param ia the InetAddress to use
     */
    public BoundBinder (final InetAddress ia) {
        this.ia = ia;
    }

    @Override
    public int getPort () {
        return 0;
    }

    @Override
    public InetAddress getInetAddress () {
        return ia;
    }
}
