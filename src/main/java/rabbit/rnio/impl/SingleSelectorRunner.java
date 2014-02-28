package rabbit.rnio.impl;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import rabbit.rnio.AcceptHandler;
import rabbit.rnio.ConnectHandler;
import rabbit.rnio.ReadHandler;
import rabbit.rnio.SelectorVisitor;
import rabbit.rnio.SocketChannelHandler;
import rabbit.rnio.WriteHandler;

/** A selector handler.
 */
@Slf4j
class SingleSelectorRunner implements Runnable {
    private final Selector selector;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executorService;

    /** The queue to get back on the main thread. */
    private final Object returnedTasksLock = new Object();
    private List<SelectorRunnable> returnedTasks1 =
            new ArrayList<>();
    private List<SelectorRunnable> returnedTasks2 =
            new ArrayList<>();

    private Thread selectorThread;

    private int id = 0;
    private static int idSequence = 0;

    public SingleSelectorRunner(final ExecutorService executorService)
            throws IOException {
        selector = Selector.open();
        this.executorService = executorService;
        id = idSequence++;
    }

    @Override public String toString() {
        return getClass().getSimpleName() + "{id: " + id + "}";
    }

    public void start(final ThreadFactory tf) {
        if (running.get()) {
            throw new IllegalStateException("Already started");
        }
        running.set(true);
        synchronized (this) {
            selectorThread = tf.newThread(this);
            selectorThread.setName(getClass().getName() + " " + id);
            selectorThread.start();
        }
    }

    public void shutdown() {
        if (!running.get()) {
            return;
        }
        running.set(false);
        try {
            selector.wakeup();
            synchronized (this) {
                if (selectorThread != null) {
                    selectorThread.join(10000);
                }
            }
            for (SelectionKey sk : selector.keys()) {
                close(sk.channel());
            }
            selector.close();
        } catch (InterruptedException | IOException e) {
            log.warn("Got exception while closing selector", e);
        }
    }

    private interface ChannelOpsUpdater {
        // Add the new handler
        void addHandler(ChannelOpsHandler coh);
    }

    private void updateSelectionKey(final SelectableChannel channel,
                                    final SocketChannelHandler handler,
                                    final ChannelOpsUpdater updater)
            throws IOException {
        SelectionKey sk = channel.keyFor(selector);
        if (!channel.isOpen()) {
            log.warn("channel: {}, is closed, won't register handler: {}, updater: {}", channel, handler, updater);
            if (sk != null && sk.isValid()) {
                final ChannelOpsHandler coh = (ChannelOpsHandler) sk.attachment();
                cancelKeyAndCloseChannel(sk);
                coh.closed();
            }
            handler.closed();
            return;
        }

        log.trace("SingleSelectorRunner.{}: updating selection key for: {}", id, sk);
        if (sk == null) {
            final ChannelOpsHandler coh = new ChannelOpsHandler();
            updater.addHandler(coh);
            sk = channel.register(selector, coh.getInterestOps(), coh);
        } else {
            final ChannelOpsHandler coh = (ChannelOpsHandler) sk.attachment();
            if (sk.isValid()) {
                updater.addHandler(coh);
                sk.interestOps(coh.getInterestOps());
            } else {
                log.trace("SingleSelectorRunner.{}: sk not valid, calling closed()", id);
                cancelKeyAndCloseChannel(sk);
                coh.closed();
                handler.closed();
            }
        }

        if (sk != null && sk.isValid()) {
            log.trace("SingleSelectorRunner.{}: sk.interestOps {}", id, sk.interestOps());
        }
    }

    public void waitForRead(final SelectableChannel channel,
                            final ReadHandler handler)
            throws IOException {
        updateSelectionKey(channel, handler, new ChannelOpsUpdater() {
            @Override
            public void addHandler(final ChannelOpsHandler coh) {
                coh.setReadHandler(handler);
            }
        });
    }

    public void waitForWrite(final SelectableChannel channel,
                             final WriteHandler handler)
            throws IOException {
        updateSelectionKey(channel, handler, new ChannelOpsUpdater() {
            @Override
            public void addHandler(final ChannelOpsHandler coh) {
                coh.setWriteHandler(handler);
            }
        });
    }

    public void waitForAccept(final SelectableChannel channel,
                              final AcceptHandler handler)
            throws IOException {
        updateSelectionKey(channel, handler, new ChannelOpsUpdater() {
            @Override
            public void addHandler(final ChannelOpsHandler coh) {
                coh.setAcceptHandler(handler);
            }
        });
    }

    public void waitForConnect(final SelectableChannel channel,
                               final ConnectHandler handler)
            throws IOException {
        updateSelectionKey(channel, handler, new ChannelOpsUpdater() {
            @Override
            public void addHandler(final ChannelOpsHandler coh) {
                coh.setConnectHandler(handler);
            }
        });
    }

    @Override
    public void run() {
        long lastRun = System.currentTimeMillis();
        int counter = 0;
        long sleepTime = 100 * 1000; // 100 seconds
        runReturnedTasks();
        while (running.get()) {
            try {
                log.trace("{}: going into select: {}", id, sleepTime);
                selector.select(sleepTime);
                final long now = System.currentTimeMillis();
                final long diff = now - lastRun;
                if (diff > 100) {
                    counter = 0;
                }

                log.trace("{}: after select, time taken: {}", id, diff);
                cancelTimeouts(now);
                int num = handleSelects();
                int rt;
                do {
                    rt = runReturnedTasks();
                    num += rt;
                } while (rt > 0);
                if (num == 0) {
                    counter++;
                }

                if (counter > 100000) {
                    tryAvoidSpinning(counter, now, diff);
                    counter = 0;
                }

                final Long nextTimeout = findNextTimeout();
                if (nextTimeout != null) {
                    sleepTime = nextTimeout - now;
                } else {
                    sleepTime = 100 * 1000;
                }

                lastRun = now;
            } catch (IOException e) {
                log.warn("{}: Failed to select, shutting down selector: {}", id, e, e);
                shutdown();
            } catch (Exception e) {
                log.warn("{}: Unknown error: {} attempting to ignore", id, e, e);
            }
        }
    }

    private Long findNextTimeout() {
        Long min = null;
        for (SelectionKey sk : selector.keys()) {
            final ChannelOpsHandler coh = (ChannelOpsHandler) sk.attachment();
            if (coh == null) {
                continue;
            }
            final Long timeout = coh.getMinimumTimeout();
            if (timeout != null) {
                if (min != null) {
                    min = min < timeout ?
                          min : timeout;
                } else {
                    min = timeout;
                }
            }
        }
        return min;
    }

    private String getStackTrace(final Throwable t) {
        final StringWriter sw = new StringWriter();
        final PrintWriter ps = new PrintWriter(sw);
        t.printStackTrace(ps);
        return sw.toString();
    }

    /* the epoll selector in linux is buggy in java/6, try a few things
     * to avoid selector spinning.
     * http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6403933
     *
     * Try to figure out the key that ought to be closed and cancel it.
     *
     * This bug above is fixed in 6u1, but keep the code in case of
     * other systems and possibly other bugs.
     */
    private void tryAvoidSpinning(final int counter, final long now, final long diff)
            throws IOException {
        log.warn("{}: Trying to avoid spinning, may close some channels: counter: {}, {}, {}", id, counter, now, diff);
        // Keys are generally writable, try to flip OP_WRITE
        // so that the selector will remove the bad keys.
        final Collection<SelectionKey> triedKeys = new HashSet<>();
        for (SelectionKey sk : selector.keys()) {
            final int ops = sk.interestOps();
            if (ops == 0) {
                triedKeys.add(sk);
                sk.interestOps(SelectionKey.OP_WRITE);
            }
        }
        selector.selectNow();
        final Set<SelectionKey> selected = selector.selectedKeys();
        for (SelectionKey sk : selected) {
            if (sk.isWritable()) {
                triedKeys.remove(sk);
            }
            sk.interestOps(0);
        }

        // If we have any keys left here they are in an unknown state
        // cancel them and hope for the best.
        if (!triedKeys.isEmpty()) {
            log.warn("{}: Some keys did not get writable, trying to close them", id);
            for (SelectionKey sk : triedKeys) {
                log.warn("{}: Non writable key: {}, attachement: {}", id, sk, sk.attachment());
                sk.cancel();
            }
            selector.selectNow();
        }
        log.info("{}: Spin evasion complete, hopefully system is ok again.", id);
    }

    private void cancelTimeouts(final long now) {
        for (SelectionKey sk : selector.keys()) {
            final ChannelOpsHandler coh = (ChannelOpsHandler) sk.attachment();
            if (coh == null) {
                continue;
            }
            if (coh.doTimeouts(now) && sk.isValid()) {
                sk.interestOps(coh.getInterestOps());
            }
        }
    }

    /** Close down a client that has timed out.
     * @param sk SelectionKey to cancel
     */
    private void cancelKeyAndCloseChannel(final SelectionKey sk) {
        sk.cancel();
        try {
            final SelectableChannel sc = sk.channel();
            sc.close();
        } catch (IOException e) {
            log.warn("{}: Failed to shutdown and close socket", id, e);
        }
    }

    private int handleSelects() {
        final Set<SelectionKey> selected = selector.selectedKeys();
        final int ret = selected.size();
        log.trace("{}: Selector handling {} selected keys", id, ret);
        for (SelectionKey sk : selected) {
            final ChannelOpsHandler coh = (ChannelOpsHandler) sk.attachment();
            log.trace("{}: ChanneOpsHandler {}", id, coh);
            if (sk.isValid()) {
                coh.handle(executorService, sk);
            } else {
                cancelKeyAndCloseChannel(sk);
                coh.closed();
            }
        }
        selected.clear();
        return ret;
    }

    private int runReturnedTasks() {
        synchronized (returnedTasksLock) {
            final List<SelectorRunnable> toRun = returnedTasks1;
            returnedTasks1 = returnedTasks2;
            returnedTasks2 = toRun;
        }
        final int s = returnedTasks2.size();
        if (s > 0) {
            log.trace("{}: Selector running {} returned tasks", id, s);
        }
        for (SelectorRunnable aReturnedTasks2 : returnedTasks2) {
            try {
                log.trace("{}: Selector running task {}", id, aReturnedTasks2);
                aReturnedTasks2.run(this);
            } catch (IOException e) {
                log.warn("Got exception when running returned task", e);
            }
        }
        returnedTasks2.clear();
        return s;
    }

    public void runSelectorTask(final SelectorRunnable sr) {
        if (!running.get()) {
            synchronized (this) {
                if (selectorThread != null) {
                    log.trace("Trying to add selector task while not running: {}", sr);
                    return;
                }
            }
        }

        synchronized (returnedTasksLock) {
            returnedTasks1.add(sr);
        }

        synchronized (this) {
            selector.wakeup();
        }
    }

    public boolean handlesChannel(final SelectableChannel channel) {
        final SelectionKey sk = channel.keyFor(selector);
        return sk != null;
    }

    public void cancel(final SelectableChannel channel,
                       final SocketChannelHandler handler) {
        final SelectionKey sk = channel.keyFor(selector);
        if (sk == null) {
            return;
        }
        final ChannelOpsHandler coh = (ChannelOpsHandler) sk.attachment();
        coh.cancel(handler);
        synchronized (sk) {
            if (sk.isValid()) {
                sk.interestOps(coh.getInterestOps());
            }
        }
    }

    public void close(final SelectableChannel channel) {
        final SelectionKey sk = channel.keyFor(selector);
        if (sk == null) {
            return;
        }
        final ChannelOpsHandler coh = (ChannelOpsHandler) sk.attachment();
        cancelKeyAndCloseChannel(sk);
        coh.closed();
    }

    public void visit(final SelectorVisitor visitor) {
        visitor.selector(selector);
    }
}
