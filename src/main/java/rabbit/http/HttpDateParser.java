package rabbit.http;

import java.text.DateFormat;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** A utility class that parses date in the http headers.
 *  A date in http may be written in many different formats so try 
 *  them all.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class HttpDateParser {
    private static final SimpleDateFormat sdf1 =
            new SimpleDateFormat ("EE',' dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
    private static final SimpleDateFormat sdf2 =
            new SimpleDateFormat ("EEEE, dd-MMM-yy HH:mm:ss 'GMT'", Locale.US);
    private static final SimpleDateFormat sdf3 =
            new SimpleDateFormat ("EE MMM d HH:mm:ss yyyy", Locale.US);
    private static final SimpleDateFormat sdf4 =
            new SimpleDateFormat ("EE MMM  d HH:mm:ss yyyy", Locale.US);

    private static long offset;

    /** The default constructor.
     */
    public HttpDateParser (){
        // empty
    }

    /** Set the time offset relative GMT.
     * @param offset the time difference in millis
     */
    public static void setOffset (final long offset) {
        HttpDateParser.offset = offset;
    }

    /** Try to get a date from the given string. According to RFC 2068 
     *  We have to read 3 formats.
     * @param date the String we are trying to parse.
     * @return a Date or null if parsing was not possible.
     */
    public static Date getDate (final String date) {
        if (date == null)
            return null;

        Date d = getDate (date, sdf1, offset);
        if (d == null) {
            d = getDate (date, sdf2, offset);
            if (d == null) {
                d = getDate (date, sdf3, offset);
                if (d == null) {
                    d = getDate (date, sdf4, offset);
                }
            }
        }
        return d;
    }

    private static Date getDate (final String date, final DateFormat sdf, final long offsetUsed) {
        try {
            final ParsePosition pos = new ParsePosition (0);
            Date d;
            synchronized (sdf) {
                d = sdf.parse (date, pos);
            }
            if (pos.getIndex () == 0 || pos.getIndex () != date.length ())
                return null;
            d.setTime (d.getTime () + offsetUsed);
            return d;
        } catch (NumberFormatException e) {
            // ignore...
        }
        return null;
    }

    /** Get a String from the date.
     * @param d the Date to format.
     * @return a String describing the date in the right way.
     */
    public static String getDateString (final Date d) {
        synchronized (sdf1) {
            return sdf1.format (d);
        }
    }
}
