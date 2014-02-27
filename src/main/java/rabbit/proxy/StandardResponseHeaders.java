package rabbit.proxy;

import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.net.URL;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import rabbit.http.HttpDateParser;
import rabbit.http.HttpHeader;
import rabbit.http.StatusCode;
import rabbit.util.StackTraceUtil;

import org.apache.commons.lang.StringEscapeUtils;

/** A class that can create standard response headers.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
class StandardResponseHeaders implements HttpGenerator {
    /** The identity of the server. */
    private final String serverIdentity;
    /** The connection handling the response. */
    private final Connection con;

    public StandardResponseHeaders (final String serverIdentity, final Connection con) {
        this.serverIdentity = serverIdentity;
        this.con = con;
    }

    public String getServerIdentity () {
        return serverIdentity;
    }

    public Connection getConnection () {
        return con;
    }

    public HttpProxy getProxy () {
        return con.getProxy ();
    }

    /** Get a new HttpHeader. This is the same as
     * getHeader ("HTTP/1.0 200 OK");
     * @return a new HttpHeader.
     */
    @Override
    public HttpHeader getHeader () {
        return getHeader (rabbit.http.StatusCode._200);
    }

    /** Get a new HttpHeader initialized with some data.
     * @param sc the StatusCode to get a header for
     * @return a new HttpHeader.
     */
    public HttpHeader getHeader (final StatusCode sc) {
        final HttpHeader ret = new HttpHeader ();
        ret.setStatusLine (sc.getStatusLine ("HTTP/1.1"));
        ret.setHeader ("Server", serverIdentity);
        ret.setHeader ("Content-type", "text/html; charset=utf-8");
        ret.setHeader ("Cache-Control", "no-cache");
        // Set pragma for compatibility with old browsers.
        ret.setHeader ("Pragma", "no-cache");
        ret.setHeader ("Date", HttpDateParser.getDateString (new Date ()));
        return ret;
    }

    /** Get a 200 Ok header
     * @return a 200 HttpHeader .
     */
    @Override
    public HttpHeader get200 () {
        return getHeader (rabbit.http.StatusCode._200);
    }

    /** Get a 302 header
     * @return a 302 HttpHeader
     */
    @Override
    public HttpHeader get302 (final String newURL){
        final HttpHeader header = getHeader (rabbit.http.StatusCode._302);
        header.setHeader("Location", newURL);
        return header;
    }

    /** Get a 400 Bad Request header for the given exception.
     * @param exception the Exception handled.
     * @return a HttpHeader for the exception.
     */
    private static final String UTF8 = "UTF-8";
    @Override
    public HttpHeader get400 (final Exception exception) {
        // in most cases we should have a header out already, but to be sure...
        final HttpHeader header = getHeader (rabbit.http.StatusCode._400);
        final String page = HtmlPage.getPageHeader (con, rabbit.http.StatusCode._400) +
                            "Unable to handle request:<br><b><pre>\n" +
                            StringEscapeUtils.escapeHtml (exception.toString ()) +
                            "</pre></b></body></html>\n";
        header.setContent (page, UTF8);
        return header;
    }

    /** Get a 403 Forbidden header.
     * @return a HttpHeader.
     */
    @Override
    public HttpHeader get403 () {
        // in most cases we should have a header out already, but to be sure...
        final HttpHeader header = getHeader (rabbit.http.StatusCode._403);
        final String page = HtmlPage.getPageHeader (con, rabbit.http.StatusCode._403) +
                            "That is forbidden</body></html>";
        header.setContent (page, UTF8);
        return header;
    }

    /** Get a 404 File not found.
     * @return a HttpHeader.
     */
    @Override
    public HttpHeader get404 (final String file) {
        // in most cases we should have a header out already, but to be sure...
        final HttpHeader header = getHeader (rabbit.http.StatusCode._404);
        final String page = HtmlPage.getPageHeader (con, rabbit.http.StatusCode._404) +
                            "File '" + StringEscapeUtils.escapeHtml (file) +
                            "' not found.</body></html>";
        header.setContent (page, UTF8);
        return header;
    }

    /** Get a 414 Request-URI Too Long
     * @return a suitable HttpHeader.
     */
    @Override
    public HttpHeader get414 () {
        final HttpHeader header = getHeader (rabbit.http.StatusCode._414);
        final String page = HtmlPage.getPageHeader (con, rabbit.http.StatusCode._414) + "</body></html>\n";
        header.setContent (page, UTF8);
        return header;
    }

    /** Get a 500 Internal Server Error header for the given exception.
     * @param exception the Exception made.
     * @return a suitable HttpHeader.
     */
    @Override
    public HttpHeader get500 (final String url, final Throwable exception) {
        // in most cases we should have a header out already, but to be sure...
        // normally this only thrashes the page... Too bad.
        final HttpHeader header = getHeader (rabbit.http.StatusCode._500);
        final String page = HtmlPage.getPageHeader (con, rabbit.http.StatusCode._500) +
                            "Error loading web page<BR>" +
                            "Error is:<BR><pre>\n" +
                            StackTraceUtil.getStackTrace (exception) +
                            "</pre><br><hr noshade>\n</body></html>\n";
        header.setContent (page, UTF8);
        return header;
    }

    private static final String WWW = "www.";
    private static final String[][] placeTransformers = {
            {WWW, ""},
            {"", ".com"},
            {WWW, ".com"},
            {"", ".org"},
            {WWW, ".org"},
            {"", ".net"},
            {WWW, ".net"}
    };

    /** Get a 504 Gateway Timeout for the given exception.
     * @param e the Exception made.
     * @return a suitable HttpHeader.
     */
    @Override
    public HttpHeader get504 (final String uri, final Throwable e) {
        final HttpHeader header = getHeader (rabbit.http.StatusCode._504);
        try {
            final boolean dnsError = (e instanceof UnknownHostException);
            final URL u = new URL (uri);
            final StringBuilder content =
                    new StringBuilder (HtmlPage.getPageHeader (con, rabbit.http.StatusCode._504));
            if (dnsError) {
                content.append("Server not found");
            } else {
                content.append("Unable to handle request");
            }

            content.append (":<br><b>" +
                            StringEscapeUtils.escapeHtml (e.getMessage ()));

            content.append ("\n\n<br>Did you mean to go to: ");
            content.append (getPlaces (u));
            String message = "";
            if (!dnsError) {
                message = "<xmp>" + StackTraceUtil.getStackTrace(e) + "</xmp>";
            }
            content.append ("</b><br>" + message + "</body></html>\n");

            header.setContent (content.toString (), UTF8);
        } catch (MalformedURLException ex) {
            throw new RuntimeException (ex);
        }

        return header;
    }

    public StringBuilder getPlaces (final URL u) {
        final StringBuilder content = new StringBuilder ();
        content.append ("<ul>");
        final Set<String> places = new HashSet<String> ();
        for (int i = 0; i < placeTransformers.length; i++) {
            final String pre = placeTransformers[i][0];
            final String suf = placeTransformers[i][1];
            final String place = getPlace (u, pre, suf);
            if (place != null && !places.contains (place)) {
                content.append ("<li><a href=\"" +
                                StringEscapeUtils.escapeHtml (place) +
                                "\">" +
                                StringEscapeUtils.escapeHtml (place) +
                                "</a></li>\n");
                places.add (place);
            }
        }
        content.append ("</ul>");
        return content;
    }

    private String getPlace (final URL u, String hostPrefix, String hostSuffix) {
        final String host = u.getHost ();
        if (host.startsWith (hostPrefix)) {
            hostPrefix = "";
        }
        if (host.endsWith (hostSuffix)) {
            hostSuffix = "";
        }
        if (hostPrefix.equals ("") && hostSuffix.equals ("")) {
            return null;
        }
        return u.getProtocol () + "://" + hostPrefix + u.getHost () +
               hostSuffix +
               (u.getPort () == -1 ? "" : ":" + u.getPort ()) +
               u.getFile ();
    }
}
