package rabbit.handler;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;

import rabbit.http.HttpHeader;
import rabbit.httpio.BlockListener;
import rabbit.httpio.BlockSender;
import rabbit.httpio.BlockSentListener;
import rabbit.httpio.ChunkEnder;
import rabbit.httpio.HttpHeaderSender;
import rabbit.httpio.HttpHeaderSentListener;
import rabbit.httpio.ResourceSource;
import rabbit.httpio.TransferHandler;
import rabbit.httpio.TransferListener;
import rabbit.io.BufferHandle;
import rabbit.io.FileHelper;
import rabbit.proxy.Connection;
import rabbit.proxy.HttpProxy;
import rabbit.proxy.TrafficLoggerHandler;
import rabbit.rnio.WriteHandler;
import rabbit.util.SProperties;

/** This class is an implementation of the Handler interface.
 *  This handler does no filtering, it only sends the data as
 *  effective as it can.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
@Slf4j
public class BaseHandler
        implements Handler, HandlerFactory, HttpHeaderSentListener, BlockListener,
                   BlockSentListener {
    /** The Connection handling the request.*/
    Connection con;
    /** The traffic logger handler. */
    TrafficLoggerHandler tlh;
    /** The actual request made. */
    private HttpHeader request;
    /** The actual response. */
    private HttpHeader response;
    /** The resource */
    ResourceSource content;

    /** The length of the data being handled or -1 if unknown.*/
    private long size = -1;
    /** The total amount of data that we read. */
    private long totalRead = 0;

    /** The flag for the last empty chunk */
    private boolean emptyChunkSent = false;

    /** For creating the factory.
     */
    public BaseHandler() {
        // empty
    }

    /** Create a new BaseHandler for the given request.
     * @param con the Connection handling the request.
     * @param tlh the TrafficLoggerHandler to update with traffic information
     * @param request the actual request made.
     * @param response the actual response.
     * @param content the resource.
     * @param size the size of the data being handled.
     */
    BaseHandler(final Connection con, final TrafficLoggerHandler tlh,
                final HttpHeader request, final HttpHeader response,
                final ResourceSource content, final long size) {
        this.con = con;
        this.tlh = tlh;
        this.request = request;
        this.response = response;
        if (!request.isDot9Request() && response == null) {
            throw new IllegalArgumentException("response may not be null");
        }
        this.content = content;
        this.size = size;
    }

    @Override
    public Handler getNewInstance(final Connection con, final TrafficLoggerHandler tlh,
                                  final HttpHeader header, final HttpHeader webHeader,
                                  final ResourceSource content, final long size) {
        return new BaseHandler(con, tlh, header, webHeader, content, size);
    }

    /** Handle the request.
     * A request is made in these steps:
     * <xmp>
     * sendHeader();
     * addCache();
     * prepare();
     * send();
     * finishData();
     * finish();
     * </xmp>
     * Note that finish is always called, no matter what exceptions are thrown.
     * The middle steps are most probably only performed if the previous steps
     * have all succeeded
     */
    @Override
    public void handle() {
        if (request.isDot9Request()) {
            send();
        } else {
            sendHeader();
        }
    }

    /**
     * ®return false if this handler never modifies the content.
     */
    @Override
    public boolean changesContentSize() {
        return false;
    }

    private void sendHeader() {
        try {
            final HttpHeaderSender hhs =
                    new HttpHeaderSender(con.getChannel(), con.getNioHandler(),
                                         tlh.getClient(), response, false, this);
            hhs.sendHeader();
        } catch (IOException e) {
            failed(e);
        }
    }

    @Override
    public void httpHeaderSent() {
        prepare();
    }

    /** This method is used to prepare the data for the resource being sent.
     *  This method does nothing here.
     */
    private void prepare() {
        send();
    }

    /** This method is used to finish the data for the resource being sent.
     *  This method will send an end chunk if needed and then call finish
     */
    private void finishData() {
        if (con.getChunking() && !emptyChunkSent) {
            emptyChunkSent = true;
            final BlockSentListener bsl = new Finisher();
            final ChunkEnder ce = new ChunkEnder();
            ce.sendChunkEnding(con.getChannel(), con.getNioHandler(),
                               tlh.getClient(), bsl);
        } else {
            finish(true);
        }
    }

    /** Mark the current response as a partial response.
     * @param shouldbe the number of byte that the resource ought to be
     */
    private void setPartialContent(final long shouldbe) {
        response.setHeader("RabbIT-Partial", Long.toString(shouldbe));
    }

    /** Close nesseccary channels and adjust the cached files.
     *  If you override this one, remember to call super.finish()!
     * @param good if true then the connection may be restarted,
     *             if false then the connection may not be restared
     */
    private void finish(final boolean good) {
        try {
            if (content != null) {
                content.release();
            }
            if (response != null && response.getHeader("Content-Length") != null) {
                con.setContentLength(response.getHeader("Content-length"));
            }
        } finally {
            // and clean up...
            request = null;
            response = null;
            content = null;
        }
        // Not sure why we need this, seems to call finish multiple times.
        if (con != null) {
            if (good) {
                con.logAndRestart();
            } else {
                con.logAndClose(null);
            }
        }
        tlh = null;
        con = null;
    }

    /** Check if this handler supports direct transfers.
     * @return this handler always return true.
     */
    private boolean mayTransfer() {
        return true;
    }

    void send() {
        if (mayTransfer() && content.length() > 0 && content.supportsTransfer()) {
            final TransferListener tl = new ContentTransferListener();
            final TransferHandler th =
                    new TransferHandler(con.getNioHandler(), content,
                                        con.getChannel(),
                                        tlh.getCache(), tlh.getClient(), tl);
            th.transfer();
        } else {
            content.addBlockListener(this);
        }
    }

    private class ContentTransferListener implements TransferListener {
        @Override
        public void transferOk() {
            finishData();
        }

        @Override
        public void failed(final Exception cause) {
            BaseHandler.this.failed(cause);
        }
    }

    @Override
    public void bufferRead(final BufferHandle bufHandle) {
        if (con == null) {
            // not sure why this can happen, client has closed connection.
            return;
        }
        // TODO: do this in another thread?
        final ByteBuffer buffer = bufHandle.getBuffer();
        totalRead += buffer.remaining();
        final WriteHandler bs =
                new BlockSender(con.getChannel(), con.getNioHandler(),
                                tlh.getClient(), bufHandle,
                                con.getChunking(), this);
        bs.write();
    }

    @Override
    public void blockSent() {
        content.addBlockListener(BaseHandler.this);
    }

    @Override
    public void finishedRead() {
        if (size > 0 && totalRead != size) {
            setPartialContent(size);
        }
        finishData();
    }

    private class Finisher implements BlockSentListener {
        @Override
        public void blockSent() {
            finish(true);
        }
        @Override
        public void failed(final Exception cause) {
            BaseHandler.this.failed(cause);
        }
        @Override
        public void timeout() {
            BaseHandler.this.timeout();
        }
    }

    private String getStackTrace(final Exception cause) {
        final StringWriter sw = new StringWriter();
        final PrintWriter ps = new PrintWriter(sw);
        cause.printStackTrace(ps);
        return sw.toString();
    }

    protected void deleteFile(final File f) {
        try {
            FileHelper.delete(f);
        } catch (IOException e) {
            log.warn("Failed to delete file", e);
        }
    }

    @Override
    public void failed(final Exception cause) {
        if (con != null) {
            String st;
            if (cause instanceof IOException) {
                final IOException ioe = (IOException) cause;
                final String msg = ioe.getMessage();
                switch(msg) {
                    case "Broken pipe":
                        st = ioe.toString() + ", probably cancelled pipeline";
                        break;
                    case "Connection reset by peer":
                        st = ioe.toString() + ", client aborted connection";
                        break;
                    default:
                        st = getStackTrace(cause);
                        break;
                }
            } else {
                st = getStackTrace(cause);
            }
            log.warn("BaseHandler: error handling request: {}: {}", request.getRequestURI(), st);
            con.setStatusCode("500");
            String ei = con.getExtraInfo();
            ei = ei == null ? cause.toString() : (ei + ", " + cause);
            con.setExtraInfo(ei);
        }
        finish(false);
    }

    @Override
    public void timeout() {
        if (con != null) {
            log.warn("BaseHandler: timeout: uri: {}", request.getRequestURI());
        }
        finish(false);
    }

    @Override
    public void setup(final SProperties properties, final HttpProxy proxy) {
        // nothing to do.
    }
}
