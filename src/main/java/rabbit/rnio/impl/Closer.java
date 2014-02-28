package rabbit.rnio.impl;

import java.io.Closeable;
import java.io.IOException;

/** A helper class that can close resources without throwing exceptions.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class Closer {

    /** Try to close the Closeable.
     *  If an exception is thrown when calling close() it will be logged 
     *  to the logger.
     * @param c the object to close
     */
    public static void close(final Closeable c) {
        if (c == null) {
            return;
        }
        try { c.close(); } catch (IOException ignored) {}
    }
}
