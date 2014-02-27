package rabbit.httpio;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.util.logging.Logger;
import java.util.logging.Level;

import rabbit.rnio.BufferHandler;
import rabbit.rnio.NioHandler;
import rabbit.rnio.TaskIdentifier;
import rabbit.rnio.impl.Closer;
import rabbit.rnio.impl.DefaultTaskIdentifier;
import rabbit.io.BufferHandle;
import rabbit.io.CacheBufferHandle;

/** A resource that comes from a file.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class FileResourceSource implements ResourceSource {
    protected FileChannel fc;

    // used for block handling.
    private BlockListener listener;
    private NioHandler nioHandler;
    protected BufferHandle bufHandle;

    private static final Logger logger = Logger.getLogger(FileResourceSource.class.getName());

    /** Create a new FileResourceSource using the given filename
     * @param filename the file for this resource
     * @param nioHandler the NioHandler to use for background tasks
     * @param bufHandler the BufferHandler to use when reading and writing
     * @throws IOException if the file is a valid file
     */
    public FileResourceSource(final String filename, final NioHandler nioHandler,
                              final BufferHandler bufHandler)
            throws IOException {
        this(new File(filename), nioHandler, bufHandler);
    }

    /** Create a new FileResourceSource using the given filename
     * @param f the resource
     * @param nioHandler the NioHandler to use for background tasks
     * @param bufHandler the BufferHandler to use when reading and writing
     * @throws IOException if the file is a valid file
     */
    public FileResourceSource(final File f, final NioHandler nioHandler,
                              final BufferHandler bufHandler)
            throws IOException {
        if (!f.exists()) {
            throw new FileNotFoundException("File: " + f.getName() +
                                            " not found");
        }
        if (!f.isFile()) {
            throw new FileNotFoundException("File: " + f.getName() +
                                            " is not a regular file");
        }
        final FileInputStream fis = new FileInputStream(f);
        fc = fis.getChannel();
        this.nioHandler = nioHandler;
        this.bufHandle = new CacheBufferHandle(bufHandler);
    }

    /** FileChannels can be used, will always return true.
     * @return true
     */
    @Override
    public boolean supportsTransfer() {
        return true;
    }

    @Override
    public long length() {
        try {
            return fc.size();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Error getting length", e);
            return -1;
        }
    }

    @Override
    public long transferTo(final long position, final long count,
                           final WritableByteChannel target)
            throws IOException {
        try {
            return fc.transferTo(position, count, target);
        } catch (IOException e) {
            if ("Resource temporarily unavailable".equals(e.getMessage())) {
                // http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=5103988
                // transferTo on linux throws IOException on full buffer.
                return 0;
            }
            throw e;
        }
    }

    /** Generally we do not come into this method, but it can happen..
     */
    @Override
    public void addBlockListener(final BlockListener listener) {
        this.listener = listener;
        // Get buffer on selector thread.
        bufHandle.getBuffer();
        final TaskIdentifier ti =
                new DefaultTaskIdentifier(getClass().getSimpleName(),
                                          "addBlockListener: channel: " + fc);
        nioHandler.runThreadTask(new ReadBlock(), ti);
    }

    private class ReadBlock implements Runnable {
        @Override
        public void run() {
            try {
                final ByteBuffer buffer = bufHandle.getBuffer();
                final int read = fc.read(buffer);
                if (read == -1) {
                    returnFinished();
                } else {
                    buffer.flip();
                    returnBlockRead();
                }
            } catch (IOException e) {
                returnWithFailure(e);
            }
        }
    }

    private void returnWithFailure(final Exception e) {
        bufHandle.possiblyFlush();
        listener.failed(e);
    }

    private void returnFinished() {
        bufHandle.possiblyFlush();
        listener.finishedRead();
    }

    private void returnBlockRead() {
        listener.bufferRead(bufHandle);
    }

    @Override
    public void release() {
        Closer.close(fc, logger);
        listener = null;
        nioHandler = null;
        bufHandle.possiblyFlush();
        bufHandle = null;
    }
}
