package rabbit.proxy;

import rabbit.handler.HandlerFactory;
import rabbit.http.HttpHeader;
import rabbit.httpio.ResourceSource;
import rabbit.io.BufferHandle;
import rabbit.io.CacheBufferHandle;
import rabbit.io.WebConnection;

/** A container to send around less parameters.
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
class RequestHandler {
    private BufferHandle webHandle;

    private ResourceSource content = null;
    private HttpHeader webHeader = null;
    private HttpHeader dataHook = null; // the entrys datahook if any.
    private HandlerFactory handlerFactory = null;
    private long size = -1;
    private WebConnection wc = null;

    public RequestHandler(final Connection con) {
        webHandle = new CacheBufferHandle(con.getBufferHandler());
    }

    public synchronized BufferHandle getWebHandle() {
        return webHandle;
    }

    public synchronized void setWebHandle(final BufferHandle webHandle) {
        this.webHandle = webHandle;
    }

    public synchronized HttpHeader getWebHeader() {
        return webHeader;
    }

    public  synchronized void setWebHeader(final HttpHeader webHeader) {
        this.webHeader = webHeader;
    }

    public synchronized HttpHeader getDataHook() {
        return dataHook;
    }

    public synchronized void setDataHook(final HttpHeader dataHook) {
        this.dataHook = dataHook;
    }

    public synchronized WebConnection getWebConnection() {
        return wc;
    }

    public synchronized void setWebConnection(final WebConnection wc) {
        this.wc = wc;
    }

    public synchronized ResourceSource getContent() {
        return content;
    }

    public synchronized void setContent(final ResourceSource content) {
        this.content = content;
    }

    public synchronized HandlerFactory getHandlerFactory() {
        return handlerFactory;
    }

    public synchronized void setHandlerFactory(final HandlerFactory handlerFactory) {
        this.handlerFactory = handlerFactory;
    }

    public synchronized long getSize() {
        return size;
    }

    public synchronized void setSize(final long size) {
        this.size = size;
    }
}
