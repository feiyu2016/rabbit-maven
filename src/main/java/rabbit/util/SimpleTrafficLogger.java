package rabbit.util;

/** A class to track of data flows. 
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class SimpleTrafficLogger implements TrafficLogger {
    private long read;
    private long written;
    private long transferFrom;
    private long transferTo;

    @Override
    public void read(final long read) {
        this.read += read;
    }

    @Override
    public long read() {
        return read;
    }

    @Override
    public void write(final long written) {
        this.written += written;
    }

    @Override
    public long write() {
        return written;
    }

    @Override
    public void transferFrom(final long transferred) {
        this.transferFrom += transferred;
    }

    @Override
    public long transferFrom() {
        return transferFrom;
    }

    @Override
    public void transferTo(final long transferred) {
        this.transferTo += transferred;
    }

    @Override
    public long transferTo() {
        return transferTo;
    }

    @Override
    public void clear() {
        read = 0;
        written = 0;
        transferFrom = 0;
        transferTo = 0;
    }

    @Override
    public void addTo(final TrafficLogger other) {
        other.read(read);
        other.write(written);
        other.transferFrom(transferFrom);
        other.transferTo(transferTo);
    }
}
