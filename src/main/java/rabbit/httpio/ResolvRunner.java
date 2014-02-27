package rabbit.httpio;

import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import rabbit.io.InetAddressListener;

/** A dns lookup class that runs in the background.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class ResolvRunner implements Runnable {
    private final URL url;
    private final InetAddressListener ial;

    /** Create a new resolver that does the DNS request on a background thread.
     * @param url the url to look up
     * @param ial the listener that will get the callback when the dns lookup
     *        is done
     */
    public ResolvRunner (final URL url, final InetAddressListener ial) {
        this.url = url;
        this.ial = ial;
    }

    /** Run a dns lookup and then notifies the listener on the selector thread.
     */
    @Override
    public void run () {
        try {
            final InetAddress ia = InetAddress.getByName(url.getHost());
            ial.lookupDone (ia);
        } catch (final UnknownHostException e) {
            ial.unknownHost (e);
        }
    }
}
