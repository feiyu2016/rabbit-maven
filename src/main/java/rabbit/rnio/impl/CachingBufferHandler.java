package rabbit.rnio.impl;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import rabbit.rnio.BufferHandler;

/** A buffer handler that re-uses returned buffers.
 *
 *  <p>This class uses no synchronization.
 *
 *  <p>This class only allocates direct buffers.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class CachingBufferHandler implements BufferHandler {
    private final Queue<BufferHolder> cache =
            new ConcurrentLinkedQueue<>();
    private final Queue<BufferHolder> largeCache =
            new ConcurrentLinkedQueue<>();

    private ByteBuffer getBuffer(final Queue<BufferHolder> bufs, final int size) {
        final BufferHolder r = bufs.poll();
        ByteBuffer b;
        if (r != null) {
            b = r.getBuffer();
        } else {
            b = ByteBuffer.allocateDirect(size);
        }
        b.clear();
        return b;
    }

    @Override
    public ByteBuffer getBuffer() {
        return getBuffer(cache, 4096);
    }

    private void addCache(final Collection<BufferHolder> bufs, final BufferHolder bh) {
        bufs.add(bh);
    }

    @Override
    public void putBuffer(final ByteBuffer buffer) {
        if (buffer == null) {
            throw new IllegalArgumentException("null buffer not allowed");
        }
        final BufferHolder bh = new BufferHolder(buffer);
        if (buffer.capacity() == 4096) {
            addCache(cache, bh);
        } else {
            addCache(largeCache, bh);
        }
    }

    @Override
    public ByteBuffer growBuffer(final ByteBuffer buffer) {
        final ByteBuffer lb = getBuffer(largeCache, 128 * 1024);
        if (buffer != null) {
            lb.put(buffer);
            putBuffer(buffer);
        }
        return lb;
    }

    private static final class BufferHolder {
        private final ByteBuffer buffer;

        public BufferHolder(final ByteBuffer buffer) {
            this.buffer = buffer;
        }

        // Two holders are equal if they hold the same buffer
        @Override public boolean equals(final Object o) {
            if (o == null) {
                return false;
            }
            if (o == this) {
                return true;
            }

            // ByteBuffer.equals depends on content, not what I want.
            return o instanceof BufferHolder && ((BufferHolder) o).buffer == buffer;
        }

        @Override public int hashCode() {
            // ByteBuffer.hashCode depends on its contents.
            return System.identityHashCode(buffer);
        }

        public ByteBuffer getBuffer() {
            return buffer;
        }
    }
}
