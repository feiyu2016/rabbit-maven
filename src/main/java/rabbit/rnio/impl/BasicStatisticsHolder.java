package rabbit.rnio.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import rabbit.rnio.StatisticsHolder;
import rabbit.rnio.TaskIdentifier;
import rabbit.rnio.statistics.CompletionEntry;
import rabbit.rnio.statistics.TotalTimeSpent;

/** A holder of statistics for tasks.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class BasicStatisticsHolder implements StatisticsHolder {
    // Map is group id to TaskIdentifier
    private Map<String, List<TaskIdentifier>> pendingTasks =
	new HashMap<String, List<TaskIdentifier>> ();

    // Map is group id to TaskIdentifier
    private Map<String, List<TaskIdentifier>> runningTasks =
	new HashMap<String, List<TaskIdentifier>> ();

    private int maxLatest = 10;
    // Map is group id to CompletionEntry
    private Map<String, List<CompletionEntry>> latest =
	new HashMap<String, List<CompletionEntry>> ();

    private int maxLongest = 10;
    // Map is group id to CompletionEntry
    private Map<String, List<CompletionEntry>> longest =
	new HashMap<String, List<CompletionEntry>> ();

    private Map<String, TotalTimeSpent> total =
	new HashMap<String, TotalTimeSpent> ();

    private <T> List<T> getList (final String id,
								 final Map<String, List<T>> tasks) {
	List<T> ls = tasks.get (id);
	if (ls == null) {
	    ls = new ArrayList<T> ();
	    tasks.put (id, ls);
	}
	return ls;
    }

    private void addTask (final TaskIdentifier ti,
						  final Map<String, List<TaskIdentifier>> tasks) {
	getList (ti.getGroupId (), tasks).add (ti);
    }

    private void removeTask (final TaskIdentifier ti,
							 final Map<String, List<TaskIdentifier>> tasks) {
	final List<TaskIdentifier> ls = tasks.get (ti.getGroupId ());
	if (ls == null)
	    throw new NullPointerException ("No pending taks for group: " +
					    ti.getGroupId ());
	if (!ls.remove (ti))
	    throw new IllegalArgumentException ("Given task was not pending: " +
						ti);
    }

    public synchronized void addPendingTask (final TaskIdentifier ti) {
	addTask (ti, pendingTasks);
    }

    public synchronized void changeTaskStatusToRunning (final TaskIdentifier ti) {
	removeTask (ti, pendingTasks);
	addTask (ti, runningTasks);
    }

    public synchronized void changeTaskStatusToFinished (final TaskIdentifier ti,
							 final boolean wasOk,
							 final long timeSpent) {
	removeTask (ti, runningTasks);
	final CompletionEntry ce = new CompletionEntry (ti, wasOk, timeSpent);
	addToLatest (ce);
	addToLongest (ce);
	addToTotal (ce);
    }

    private void addToLatest (final CompletionEntry ce) {
	final List<CompletionEntry> ls = getList (ce.ti.getGroupId (), latest);
	ls.add (ce);
	if (ls.size () > maxLatest)
	    ls.remove (0);
    }

    private void addToLongest (final CompletionEntry ce) {
	final List<CompletionEntry> ls = getList (ce.ti.getGroupId (), longest);
	if (ls.isEmpty ()) {
	    ls.add (ce);
	} else if (addSorted (ce, ls) && (ls.size () > maxLongest)){
		ls.remove (ls.size () - 1);
	}
    }

    private boolean addSorted (final CompletionEntry ce,
							   final List<CompletionEntry> ls) {
	final int s = ls.size ();
	for (int i = 0; i < s; i++) {
	    if (ce.timeSpent > ls.get (i).timeSpent) {
		ls.add (i, ce);
		return true;
	    }
	}
	if (s < maxLongest) {
	    ls.add (ce);
	    return true;
	}
	return false;
    }

    private void addToTotal (final CompletionEntry ce) {
	TotalTimeSpent tts = total.get (ce.ti.getGroupId ());
	if (tts == null) {
	    tts = new TotalTimeSpent ();
	    total.put (ce.ti.getGroupId (), tts);
	}
	tts.update (ce);
    }

    private <K, V> Map<K, List<V>> copy (final Map<K, List<V>> m) {
	final Map<K, List<V>> ret = new HashMap<K, List<V>> ();
	for (Map.Entry<K, List<V>> me : m.entrySet ())
	    ret.put (me.getKey (), new ArrayList<V> (me.getValue ()));
	return ret;
    }

    public synchronized Map<String, List<TaskIdentifier>> getPendingTasks () {
	return copy (pendingTasks);
    }

    public synchronized Map<String, List<TaskIdentifier>> getRunningTasks () {
	return copy (runningTasks);
    }

    public synchronized Map<String, List<CompletionEntry>> getLatest () {
	return copy (latest);
    }

    public synchronized Map<String, List<CompletionEntry>> getLongest () {
	return copy (longest);
    }

    public synchronized Map<String, TotalTimeSpent> getTotalTimeSpent () {
	return Collections.unmodifiableMap (total);
    }
}
