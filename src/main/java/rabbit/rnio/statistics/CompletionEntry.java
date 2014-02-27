package rabbit.rnio.statistics;

import rabbit.rnio.TaskIdentifier;

/** Information about a completed task.
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public final class CompletionEntry {
    /** The identifier of the task that has been completed. */
    public final TaskIdentifier ti;
    /** The status of the completed job. */
    public final boolean wasOk;
    /** The number of millis spent on the task. */
    public final long timeSpent;

    /** Create a new CompletionEntry
     * @param ti the identifier of the task that completed
     * @param wasOk true if the task completed without errors, false otherwise
     * @param timeSpent the wall clock time for the task
     */
    public CompletionEntry (final TaskIdentifier ti, 
			    final boolean wasOk, 
			    final long timeSpent) {
	this.ti = ti;
	this.wasOk = wasOk;
	this.timeSpent = timeSpent;
    }
}
