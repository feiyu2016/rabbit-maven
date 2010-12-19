package org.khelekore.rnio.impl;

import java.util.concurrent.ThreadFactory;

/** A very simple thread pool that only creates new threads.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class SimpleThreadPool implements ThreadFactory {
    @Override public Thread newThread(Runnable r) {
	return new Thread(r);
    }
}