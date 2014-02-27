package rabbit.http;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

/** A header suitable for multi part handling.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class MultipartHeader extends GeneralHeader {
    private String header;

    /** Used for Externalizable, not to be used for other purposes. */
    MultipartHeader() {
        // empty
    }

    /** Create a a new multi-part header using the given separator
     * @param header the separator String.
     */
    public MultipartHeader(final String header) {
        this.header = header;
    }

    @Override public String toString() {
        return header + Header.CRLF + super.toString();
    }

    /** Write this MultipartHeader to the given output.
     * @param out the output to write this header to.
     * @throws IOException if writing fails
     */
    public void writeExternal(final ObjectOutput out) throws IOException {
        out.writeObject(header);
    }

    /** Read in the state of this header from the given input.
     * @param in the input to read from
     * @throws IOException if reading fails
     * @throws ClassNotFoundException if the input has bad data.
     */
    public void readExternal(final ObjectInput in) throws IOException, ClassNotFoundException {
        header = (String) in.readObject();
    }
}
