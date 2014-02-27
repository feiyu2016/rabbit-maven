package rabbit.filter;

import java.util.Locale;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;
import rabbit.http.HttpDateParser;
import rabbit.http.HttpHeader;
import rabbit.io.ProxyChain;
import rabbit.io.Resolver;
import rabbit.proxy.Connection;
import rabbit.proxy.HttpProxy;
import rabbit.util.Base64;
import rabbit.util.SProperties;

/** This is a class that filter http headers to make them nice.
 *  This filter sets up username and password if supplied and
 *  also sets up keepalive.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class HttpBaseFilter implements HttpFilter {
    /** Constant for requests that want an unfiltered resource. */
    public static final String NOPROXY = "http://noproxy.";
    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;

    private final List<String> removes = new ArrayList<String> ();
    private static final Logger logger = Logger.getLogger (HttpBaseFilter.class.getName ());

    /** We got a proxy authentication, handle it...
     * @param uap the authentication string.
     * @param con the Connection.
     */
    private static final String BASIC = "Basic";
    private void handleProxyAuthentication (String uap, final Connection con) {
        // guess we should handle digest here also.. :-/
        if (uap.startsWith (BASIC)) {
            uap = uap.substring (BASIC.length ());
            final String userapass = Base64.decode (uap);
            int i;
            if ((i = userapass.indexOf (':')) > -1) {
                final String userid = userapass.substring (0, i);
                final String pass = userapass.substring (i + 1);
                con.setUserName (userid);
                con.setPassword (pass);
            }
        }
    }

    /** Handle the authentications.
     *  If we have a proxy-authentication we set the
     *  connections username and password.
     *  We also rewrite authentications in the URL to a standard header,
     *  since java does not handle them.
     * @param header the Request.
     * @param con the Connection.
     */
    private void handleAuthentications (final HttpHeader header, final Connection con) {
        handleAuthentications (header, con, "Proxy-Authorization");
    }

    /** Handle the authentications.
     *  If we have a authentication token of the given type we set the
     *  connections username and password.
     *  We also rewrite authentications in the URL to a standard header,
     *  since java does not handle them.
     * @param header the Request.
     * @param con the Connection.
     * @param type the authentication type "Proxy-Authentication" or
     *             "Authorization"
     */
    private void handleAuthentications (final HttpHeader header, final Connection con,
                                        final String type) {
        final String uap = header.getHeader (type);
        if (uap != null) {
            handleProxyAuthentication(uap, con);
        }

	/*
	 * Java URL:s doesn't handle user/pass in the URL as in rfc1738:
	 * //<user>:<password>@<host>:<port>/<url-path>
	 *
	 * Convert these to an Authorization header and remove from URI.
	 */
        final String requestURI = header.getRequestURI();

        int s3, s4, s5;
        if ((s3 = requestURI.indexOf("//")) >= 0
            && (s4 = requestURI.indexOf('/', s3 + 2)) >= 0
            && (s5 = requestURI.indexOf('@', s3 + 2)) >= 0
            && s5 < s4) {

            final String userPass = requestURI.substring(s3 + 2, s5);
            header.setHeader("Authorization", BASIC + " " +
                                              Base64.encode(userPass));

            header.setRequestURI(requestURI.substring(0, s3 + 2) +
                                 requestURI.substring(s5 + 1));
        }
    }

    /** Check if this is a noproxy request, and if so handle it.
     * @param requri the requested resource.
     * @param header the actual request.
     * @param con the Connection.
     * @return the new request URI
     */
    private String handleNoProxyRequest (String requri, final HttpHeader header) {
        requri = "http://" + requri.substring (NOPROXY.length ());
        header.setRequestURI (requri);
        return requri;
    }

    /** Check that the requested URL is valid and if it is a meta request.
     * @param requri the requested resource.
     * @param header the actual request.
     * @param con the Connection.
     * @return null if the request is allowed or an error response header
     */
    private HttpHeader handleURLSetup (final String requri, final HttpHeader header,
                                       final Connection con) {
        try {
            // is this request to our self?
            if (requri != null && requri.length () > 0
                && requri.charAt (0) == '/') {
                return con.getHttpGenerator().get404(requri);
            }
            final URL url = new URL (requri);
            header.setHeader ("Host",
                              url.getPort () > -1 ?
                              url.getHost () + ":" + url.getPort () :
                              url.getHost ());
        } catch (MalformedURLException e) {
            return con.getHttpGenerator ().get400 (e);
        }
        return null;
    }

    /** Remove all "Connection" tokens from the header.
     * @param header the HttpHeader that needs to be cleaned.
     */
    private void removeConnectionTokens (final HttpHeader header) {
        final List<String> cons = header.getHeaders ("Connection");
        final int l = cons.size ();
        for (int i = 0; i < l; i++) {
            final String val = cons.get (i);
	    /* ok, split it... */
            int s;
            int start = 0;
            while (start < val.length ()) {
                while (val.length () > start + 1
                       && (val.charAt (start) == ' '
                           || val.charAt (start) == ',')) {
                    start++;
                }
                if (val.length () > start + 1 && val.charAt (start) == '"') {
                    start++;
                    s = val.indexOf ('"', start);
                    while (s >= -1
                           && val.charAt (s - 1) == '\\'
                           && val.length () > s + 1) {
                        s = val.indexOf('"', s + 1);
                    }
                    if (s == -1) {
                        s = val.length();
                    }
                    String t = val.substring (start, s).trim ();
		    /* ok, unquote the value... */
                    StringBuilder sb = new StringBuilder (t.length ());
                    for (int c = 0; c < t.length (); c++) {
                        final char z = t.charAt (c);
                        if (z != '\\') {
                            sb.append(z);
                        }
                    }
                    t = sb.toString ();
                    header.removeHeader (t);
                    s = val.indexOf (',', s + 1);
                    if (s == -1) {
                        start = val.length();
                    } else {
                        start = s + 1;
                    }
                } else {
                    s = val.indexOf (',', start + 1);
                    if (s == -1) {
                        s = val.length();
                    }
                    final String t = val.substring (start, s).trim ();
                    header.removeHeader (t);
                    start = s + 1;
                }
            }
        }
    }

    private HttpHeader checkMaxForwards (final Connection con, final HttpHeader header,
                                         final String val) {
        try {
            final BigInteger bi = new BigInteger (val);
            if (bi.equals (ZERO)) {
                if (header.getMethod ().equals ("TRACE")) {
                    final HttpHeader ret = con.getHttpGenerator ().get200 ();
                    ret.setContent (header.toString (), "UTF-8");
                    return ret;
                }
                final HttpHeader ret = con.getHttpGenerator ().get200 ();
                ret.setHeader ("Allow", "GET,HEAD,POST,OPTIONS,TRACE");
                ret.setHeader ("Content-Length", "0");
                return ret;
            }
            final BigInteger b3 = bi.subtract (ONE);
            header.setHeader ("Max-Forwards", b3.toString ());
        } catch (NumberFormatException e) {
            logger.warning ("Bad number for Max-Forwards: '" + val + "'");
        }
        return null;
    }

    public HttpHeader doHttpInFiltering (final SocketChannel socket,
                                         final HttpHeader header, final Connection con) {
        // ok, no real header then don't do a thing.
        if (header.isDot9Request ()) {
            con.setKeepalive (false);
            con.setChunking (false);
            return null;
        }

        handleAuthentications (header, con);

        boolean maychunk;
        boolean mayKeepAlive;

        final String requestVersion = header.getHTTPVersion ().toUpperCase (Locale.US);
        if (requestVersion.equals ("HTTP/1.1")) {
            final String host = header.getHeader ("Host");
            if (host == null) {
                final Exception exe =
                        new Exception ("No host header set in HTTP/1.1 request");
                return con.getHttpGenerator ().get400 (exe);
            }
            maychunk = true;
            String closeit = header.getHeader ("Proxy-Connection");
            if (closeit == null) {
                closeit = header.getHeader("Connection");
            }
            mayKeepAlive = (closeit == null
                            || !closeit.equalsIgnoreCase ("close"));
        } else {
            header.setHTTPVersion ("HTTP/1.1");
            maychunk = false;
            // stupid netscape to not follow the standards,
            // only "Connection" should be used...
            String keepalive = header.getHeader ("Proxy-Connection");
            mayKeepAlive = (keepalive != null
                            && keepalive.equalsIgnoreCase ("Keep-Alive"));
            if (!mayKeepAlive) {
                keepalive = header.getHeader ("Connection");
                mayKeepAlive = (keepalive != null
                                && keepalive.equalsIgnoreCase ("Keep-Alive"));
            }
        }

        final String method = header.getMethod ().trim ();
        if (method.equals ("HEAD")) {
            maychunk = false;
        }
        con.setChunking (maychunk);

        final String mf = header.getHeader ("Max-Forwards");
        if (mf != null) {
            final HttpHeader ret = checkMaxForwards (con, header, mf);
            if (ret != null) {
                return ret;
            }
        }

        con.setKeepalive (mayKeepAlive);

        String requri = header.getRequestURI ();
        if (requri.toLowerCase (Locale.US).startsWith (NOPROXY)) {
            requri = handleNoProxyRequest(requri, header);
        }

        final HttpHeader headerr = handleURLSetup (requri, header, con);
        if (headerr != null) {
            return headerr;
        }

        removeConnectionTokens (header);
        for (String r : removes) {
            header.removeHeader (r);
        }

        final ProxyChain proxyChain = con.getProxy ().getProxyChain ();
        final Resolver resolver = proxyChain.getResolver (requri);
        if (resolver.isProxyConnected ()) {
            final String auth = resolver.getProxyAuthString ();
            // it should look like this (using RabbIT:RabbIT):
            // Proxy-authorization: Basic UmFiYklUOlJhYmJJVA==
            header.setHeader ("Proxy-authorization",
                              BASIC + " " + Base64.encode (auth));
        }

        // try to use keepalive backwards.
        // This is not needed since it is a HTTP/1.1 request.
        // header.setHeader ("Connection", "Keep-Alive");

        return null;
    }

    private boolean checkCacheControl (final String cachecontrol) {
        final String[] caches = cachecontrol.split (",");
        for (String cached : caches) {
            cached = cached.trim ();
            if (cached.equals ("no-store")) {
                return false;
            }
            if (cached.equals ("private")) {
                return false;
            }
        }
        return true;
    }

    public HttpHeader doHttpOutFiltering (final SocketChannel socket,
                                          final HttpHeader header, final Connection con) {
        boolean useCache = true;
        //String cached = header.getHeader ("Pragma");
        final List<String> ccs = header.getHeaders ("Cache-Control");
        for (String cached : ccs) {
            if (cached != null) {
                useCache &= checkCacheControl(cached);
            }
        }

        final String status = header.getStatusCode ().trim ();
        if (!(status.equals ("200") || status.equals ("206")
              || status.equals ("304"))) {
            con.setKeepalive (false);
            useCache = false;
        }

        String age = header.getHeader ("Age");
        long secs = 0;
        if (age == null) {
            age = "0";
        }
        try {
            secs = Long.parseLong (age);
        } catch (NumberFormatException e) {
            // ignore, we already have a warning for this..
        }
        if (secs > 60 * 60 * 24) {
            header.setHeader("Warning", "113 RabbIT \"Heuristic expiration\"");
        }

        header.setResponseHTTPVersion ("HTTP/1.1");

        removeConnectionTokens (header);
        for (String r : removes) {
            header.removeHeader (r);
        }

        final String d = header.getHeader ("Date");
        if (d == null) {
            // ok, maybe we should check if there is an Age set
            // otherwise we can do like this.
            header.setHeader ("Date",
                              HttpDateParser.getDateString (new Date ()));
        }

        final String cl = header.getHeader ("Content-Length");
        if (cl == null && !con.getChunking ()) {
            con.setKeepalive(false);
        }

        return null;
    }

    public HttpHeader doConnectFiltering (final SocketChannel socket,
                                          final HttpHeader header, final Connection con) {
        return null;
    }

    public void setup (final SProperties properties, final HttpProxy proxy) {
        removes.clear ();
        final String rs = "Connection,Proxy-Connection,Keep-Alive,Public,Transfer-Encoding,Upgrade,Proxy-Authorization,TE,Proxy-Authenticate,Trailer";
        final String[] sts = rs.split (",");
        for (String r : sts) {
            removes.add (r.trim ());
        }
    }
}
