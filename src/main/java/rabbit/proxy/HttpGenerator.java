package rabbit.proxy;

import rabbit.http.HttpHeader;

/** An interface describing the methods for http header generation.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public interface HttpGenerator {
    /** Get a new HttpHeader. This is the same as 
     * getHeader ("HTTP/1.0 200 OK");
     * @return a new HttpHeader.
     */
    HttpHeader getHeader ();

    /** Get a 200 Ok header
     * @return a 200 HttpHeader .
     */
    HttpHeader get200 ();

    /** Returns a 302 found header
     * @return a 302 HttpHeader .
     */
    HttpHeader get302 (String newUrl);

    /** Get a 400 Bad Request header for the given exception.
     * @param exception the Exception handled.
     * @return a HttpHeader for the exception.
     */
    HttpHeader get400 (Exception exception);

    /** Get a 403 Forbidden header.
     * @return a HttpHeader.
     */
    HttpHeader get403 ();

    /** Get a 404 File not found header.
     * @param file the file that was not found
     * @return a HttpHeader.
     */
    HttpHeader get404 (String file);

    /** Get a 414 Request-URI Too Long header.
     * @return a suitable HttpHeader.
     */
    HttpHeader get414 ();

    /** Get a 500 Internal Server Error header for the given exception.
     * @param requestURL the url that failed
     * @param exception the Exception made.
     * @return a suitable HttpHeader.
     */
    HttpHeader get500 (String requestURL, Throwable exception);

    /** Get a 504 Gateway Timeout for the given exception.
     * @param requestURL the url of the request
     * @param exception the Exception made.
     * @return a suitable HttpHeader.
     */
    HttpHeader get504 (String requestURL, Throwable exception);
}
