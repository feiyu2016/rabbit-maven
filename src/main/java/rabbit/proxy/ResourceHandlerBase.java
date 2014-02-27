package rabbit.proxy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import rabbit.rnio.ReadHandler;
import rabbit.io.BufferHandle;
import rabbit.io.WebConnection;

/** A base for client resource transfer classes.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
abstract class ResourceHandlerBase implements ClientResourceHandler {
    protected final Connection con;
    protected final BufferHandle bufHandle;
    protected final TrafficLoggerHandler tlh;
    protected WebConnection wc;
    protected ClientResourceTransferredListener listener;
    private List<ClientResourceListener> resourceListeners;

    public ResourceHandlerBase(final Connection con,
                               final BufferHandle bufHandle,
                               final TrafficLoggerHandler tlh) {
        this.con = con;
        this.bufHandle = bufHandle;
        this.tlh = tlh;
    }

    /**  Will store the variables and call doTransfer ()
     */
    @Override
    public void transfer(final WebConnection wc,
                         final ClientResourceTransferredListener crtl) {
        this.wc = wc;
        this.listener = crtl;
        doTransfer();
    }

    protected void doTransfer() {
        if (!bufHandle.isEmpty()) {
            sendBuffer();
        } else {
            waitForRead();
        }
    }

    @Override
    public void addContentListener(final ClientResourceListener crl) {
        if (resourceListeners == null) {
            resourceListeners = new ArrayList<>();
        }
        resourceListeners.add(crl);
    }

    public void fireResouceDataRead(final BufferHandle bufHandle) {
        if (resourceListeners == null) {
            return;
        }
        for (ClientResourceListener crl : resourceListeners) {
            crl.resourceDataRead(bufHandle);
        }
    }

    abstract void sendBuffer();

    protected void waitForRead() {
        bufHandle.possiblyFlush();
        final ReadHandler sh = new Reader();
        con.getNioHandler().waitForRead(con.getChannel(), sh);
    }

    private class Reader implements ReadHandler {
        private final Long timeout = con.getNioHandler().getDefaultTimeout();

        @Override
        public void read() {
            try {
                final ByteBuffer buffer = bufHandle.getBuffer();
                final int read = con.getChannel().read(buffer);
                if (read == 0) {
                    waitForRead();
                } else if (read == -1) {
                    failed(new IOException("Failed to read request"));
                } else {
                    tlh.getClient().read(read);
                    buffer.flip();
                    sendBuffer();
                }
            } catch (IOException e) {
                listener.failed(e);
            }
        }

        @Override
        public void closed() {
            bufHandle.possiblyFlush();
            listener.failed(new IOException("Connection closed"));
        }

        @Override
        public void timeout() {
            bufHandle.possiblyFlush();
            listener.timeout();
        }

        @Override
        public boolean useSeparateThread() {
            return false;
        }

        @Override
        public String getDescription() {
            return toString();
        }

        @Override
        public Long getTimeout() {
            return timeout;
        }
    }

    public void timeout() {
        bufHandle.possiblyFlush();
        listener.timeout();
    }

    public void failed(final Exception e) {
        bufHandle.possiblyFlush();
        listener.failed(e);
    }
}
