package rabbit.rnio.impl;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import rabbit.rnio.AcceptHandler;
import rabbit.rnio.ConnectHandler;
import rabbit.rnio.ReadHandler;
import rabbit.rnio.SelectorVisitor;
import rabbit.rnio.SocketChannelHandler;
import rabbit.rnio.WriteHandler;

/** A selector handler.
 */
class SingleSelectorRunner implements Runnable {
    private final Selector selector;
    private final AtomicBoolean running = new AtomicBoolean (false);
    private static final Logger logger = Logger.getLogger ("rabbit.rnio");
    private final ExecutorService executorService;

    /** The queue to get back on the main thread. */
    private final Object returnedTasksLock = new Object ();
    private List<SelectorRunnable> returnedTasks1 =
            new ArrayList<SelectorRunnable> ();
    private List<SelectorRunnable> returnedTasks2 =
            new ArrayList<SelectorRunnable> ();

    private Thread selectorThread;

    private int id = 0;
    private static int idSequence = 0;

    public SingleSelectorRunner (final ExecutorService executorService)
            throws IOException {
        selector = Selector.open ();
        this.executorService = executorService;
        id = idSequence++;
    }

    @Override public String toString () {
        return getClass ().getSimpleName () + "{id: " + id + "}";
    }

    public void start (final ThreadFactory tf) {
        if (running.get ()) {
            throw new IllegalStateException("Already started");
        }
        running.set (true);
        synchronized (this) {
            selectorThread = tf.newThread (this);
            selectorThread.setName (getClass ().getName () + " " + id);
            selectorThread.start ();
        }
    }

    public void shutdown () {
        if (!running.get ()) {
            return;
        }
        running.set (false);
        try {
            selector.wakeup ();
            synchronized (this) {
                if (selectorThread != null) {
                    selectorThread.join (10000);
                }
            }
            for (SelectionKey sk : selector.keys ()) {
                close (sk.channel ());
            }
            selector.close ();
        } catch (InterruptedException e) {
            logger.log (Level.WARNING,
                        "Got exception while closing selector",
                        e);
        } catch (IOException e) {
            logger.log (Level.WARNING,
                        "Got exception while closing selector",
                        e);
        }
    }

    private interface ChannelOpsUpdater {
        // Add the new handler
        void addHandler (ChannelOpsHandler coh);
    }

    private void updateSelectionKey (final SelectableChannel channel,
                                     final SocketChannelHandler handler,
                                     final ChannelOpsUpdater updater)
            throws IOException {
        SelectionKey sk = channel.keyFor (selector);
        if (!channel.isOpen ()) {
            logger.warning ("channel: " + channel + " is closed, wont register: " +
                            "handler: " + handler + ", updater: " + updater);
            if (sk != null && sk.isValid ()) {
                final ChannelOpsHandler coh = (ChannelOpsHandler)sk.attachment ();
                cancelKeyAndCloseChannel (sk);
                coh.closed ();
            }
            handler.closed ();
            return;
        }

        if (logger.isLoggable (Level.FINEST)) {
            logger.fine("SingleSelectorRunner." + id + ": updating " +
                        "selection key for: " + sk);
        }
        if (sk == null) {
            final ChannelOpsHandler coh = new ChannelOpsHandler ();
            updater.addHandler (coh);
            sk = channel.register (selector, coh.getInterestOps (), coh);
        } else {
            final ChannelOpsHandler coh = (ChannelOpsHandler)sk.attachment ();
            if (sk.isValid ()) {
                updater.addHandler (coh);
                sk.interestOps (coh.getInterestOps ());
            } else {
                if (logger.isLoggable (Level.FINEST)) {
                    logger.fine("SingleSelectorRunner." + id +
                                ": sk not valid, calling closed ()");
                }
                cancelKeyAndCloseChannel (sk);
                coh.closed ();
                handler.closed ();
            }
        }
        if (logger.isLoggable (Level.FINEST) && sk != null && sk.isValid ()) {
            logger.fine("SingleSelectorRunner." + id + ": sk.interestOps " +
                        sk.interestOps());
        }
    }

    public void waitForRead (final SelectableChannel channel,
                             final ReadHandler handler)
            throws IOException {
        updateSelectionKey (channel, handler, new ChannelOpsUpdater () {
            public void addHandler (final ChannelOpsHandler coh) {
                coh.setReadHandler (handler);
            }
        });
    }

    public void waitForWrite (final SelectableChannel channel,
                              final WriteHandler handler)
            throws IOException {
        updateSelectionKey (channel, handler, new ChannelOpsUpdater () {
            public void addHandler (final ChannelOpsHandler coh) {
                coh.setWriteHandler (handler);
            }
        });
    }

    public void waitForAccept (final SelectableChannel channel,
                               final AcceptHandler handler)
            throws IOException {
        updateSelectionKey (channel, handler, new ChannelOpsUpdater () {
            public void addHandler (final ChannelOpsHandler coh) {
                coh.setAcceptHandler (handler);
            }
        });
    }

    public void waitForConnect (final SelectableChannel channel,
                                final ConnectHandler handler)
            throws IOException {
        updateSelectionKey (channel, handler, new ChannelOpsUpdater () {
            public void addHandler (final ChannelOpsHandler coh) {
                coh.setConnectHandler (handler);
            }
        });
    }

    public void run () {
        long lastRun = System.currentTimeMillis ();
        int counter = 0;
        long sleepTime = 100 * 1000; // 100 seconds
        runReturnedTasks ();
        while (running.get ()) {
            try {
                if (logger.isLoggable (Level.FINEST)) {
                    logger.finest(id + ": going into select: " + sleepTime);
                }
                selector.select (sleepTime);
                final long now = System.currentTimeMillis ();
                final long diff = now - lastRun;
                if (diff > 100) {
                    counter = 0;
                }

                if (logger.isLoggable (Level.FINEST)) {
                    logger.finest(id + ": after select, time taken: " + diff);
                }
                cancelTimeouts (now);
                int num = handleSelects ();
                int rt = 0;
                do {
                    rt = runReturnedTasks ();
                    num += rt;
                } while (rt > 0);
                if (num == 0) {
                    counter++;
                }

                if (counter > 100000) {
                    tryAvoidSpinning (counter, now, diff);
                    counter = 0;
                }

                final Long nextTimeout = findNextTimeout ();
                if (nextTimeout != null) {
                    sleepTime = nextTimeout.longValue() - now;
                } else {
                    sleepTime = 100 * 1000;
                }

                lastRun = now;
            } catch (IOException e) {
                logger.warning (id + ": Failed to select, " +
                                "shutting down selector: " + e +
                                "\n" + getStackTrace (e));
                shutdown ();
            } catch (Exception e) {
                logger.warning (id + ": Unknown error: " + e +
                                " attemting to ignore\n" +
                                getStackTrace (e));
            }
        }
    }

    private Long findNextTimeout () {
        Long min = null;
        for (SelectionKey sk : selector.keys ()) {
            final ChannelOpsHandler coh = (ChannelOpsHandler)sk.attachment ();
            if (coh == null) {
                continue;
            }
            final Long timeout = coh.getMinimumTimeout ();
            if (timeout != null) {
                if (min != null) {
                    min = min.longValue () < timeout.longValue () ?
                          min : timeout;
                } else {
                    min = timeout;
                }
            }
        }
        return min;
    }

    private String getStackTrace (final Throwable t) {
        final StringWriter sw = new StringWriter ();
        final PrintWriter ps = new PrintWriter (sw);
        t.printStackTrace (ps);
        return sw.toString ();
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
    private void tryAvoidSpinning (final int counter, final long now, final long diff)
            throws IOException {
        logger.warning (id + ": Trying to avoid spinning, may close some " +
                        "channels: counter: " + counter + ", now: " + now +
                        ", diff: " + diff);
        // Keys are generally writable, try to flip OP_WRITE
        // so that the selector will remove the bad keys.
        final Set<SelectionKey> triedKeys = new HashSet<SelectionKey> ();
        for (SelectionKey sk : selector.keys ()) {
            final int ops = sk.interestOps ();
            if (ops == 0) {
                triedKeys.add (sk);
                sk.interestOps (SelectionKey.OP_WRITE);
            }
        }
        selector.selectNow ();
        final Set<SelectionKey> selected = selector.selectedKeys ();
        for (SelectionKey sk : selected) {
            if (sk.isWritable ()) {
                triedKeys.remove (sk);
            }
            sk.interestOps (0);
        }

        // If we have any keys left here they are in an unknown state
        // cancel them and hope for the best.
        if (!triedKeys.isEmpty ()) {
            logger.warning (id + ": Some keys did not get writable, " +
                            "trying to close them");
            for (SelectionKey sk : triedKeys) {
                logger.warning (id + ": Non writable key: " + sk +
                                ", attachment: " + sk.attachment ());
                sk.cancel ();
            }
            selector.selectNow ();
        }
        logger.info (id + ": Spin evasion complete, " +
                     "hopefully system is ok again.");
    }

    private void cancelTimeouts (final long now) {
        for (SelectionKey sk : selector.keys ()) {
            final ChannelOpsHandler coh = (ChannelOpsHandler)sk.attachment ();
            if (coh == null) {
                continue;
            }
            if (coh.doTimeouts (now) && sk.isValid ()){
                sk.interestOps (coh.getInterestOps ());
            }
        }
    }

    /** Close down a client that has timed out.
     * @param sk SelectionKey to cancel
     */
    private void cancelKeyAndCloseChannel (final SelectionKey sk) {
        sk.cancel ();
        try {
            final SelectableChannel sc = sk.channel ();
            sc.close ();
        } catch (IOException e) {
            logger.log (Level.WARNING,
                        id + ": Failed to shutdown and close socket",
                        e);
        }
    }

    private int handleSelects () {
        final Set<SelectionKey> selected = selector.selectedKeys ();
        final int ret = selected.size ();
        if (logger.isLoggable (Level.FINEST)) {
            logger.finest(id + ": Selector handling " + ret + " selected keys");
        }
        for (SelectionKey sk : selected) {
            final ChannelOpsHandler coh = (ChannelOpsHandler)sk.attachment ();
            if (logger.isLoggable (Level.FINEST)) {
                logger.finest(id + ": ChanneOpsHandler " + coh);
            }
            if (sk.isValid ()) {
                coh.handle (executorService, sk);
            } else {
                cancelKeyAndCloseChannel (sk);
                coh.closed ();
            }
        }
        selected.clear ();
        return ret;
    }

    private int runReturnedTasks () {
        synchronized (returnedTasksLock) {
            final List<SelectorRunnable> toRun = returnedTasks1;
            returnedTasks1 = returnedTasks2;
            returnedTasks2 = toRun;
        }
        final int s = returnedTasks2.size ();
        if (s > 0 && logger.isLoggable (Level.FINEST)) {
            logger.finest(id + ": Selector running " + s + " returned tasks");
        }
        for (int i = 0; i < s; i++) {
            try {
                final SelectorRunnable sr = returnedTasks2.get (i);
                if (logger.isLoggable (Level.FINEST)) {
                    logger.finest(id + ": Selector running task " + sr);
                }
                sr.run (this);
            } catch (IOException e) {
                logger.log (Level.WARNING,
                            "Got exception when running returned task",
                            e);
            }
        }
        returnedTasks2.clear ();
        return s;
    }

    public void runSelectorTask (final SelectorRunnable sr) {
        if (!running.get ()) {
            synchronized (this) {
                if (selectorThread != null) {
                    final String err = "Trying to add selector task while not running: " + sr;
                    logger.finest (err);
                    return;
                }
            }
        }

        synchronized (returnedTasksLock) {
            returnedTasks1.add (sr);
        }

        synchronized (this) {
            selector.wakeup ();
        }
    }

    public boolean handlesChannel (final SelectableChannel channel) {
        final SelectionKey sk = channel.keyFor (selector);
        return sk != null;
    }

    public void cancel (final SelectableChannel channel,
                        final SocketChannelHandler handler) {
        final SelectionKey sk = channel.keyFor (selector);
        if (sk == null) {
            return;
        }
        final ChannelOpsHandler coh = (ChannelOpsHandler)sk.attachment ();
        coh.cancel (handler);
        synchronized (sk) {
            if (sk.isValid ()) {
                sk.interestOps(coh.getInterestOps());
            }
        }
    }

    public void close (final SelectableChannel channel) {
        final SelectionKey sk = channel.keyFor (selector);
        if (sk == null) {
            return;
        }
        final ChannelOpsHandler coh = (ChannelOpsHandler)sk.attachment ();
        cancelKeyAndCloseChannel (sk);
        coh.closed ();
    }

    public void visit (final SelectorVisitor visitor) {
        visitor.selector (selector);
    }
}
