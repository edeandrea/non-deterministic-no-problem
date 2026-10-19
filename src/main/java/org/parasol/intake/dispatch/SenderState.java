package org.parasol.intake.dispatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One sender's place in the intake: the run that's working, and the emails waiting behind it. Immutable, so the starter
 * changes it only through {@code ConcurrentHashMap.compute} and never needs a lock.
 * <p>
 *   Every transition builds a fresh state, so {@link #starting} is set only by the transition that decided to start an email,
 *   and only the caller that applied it sees it. That's what keeps an email from being started twice.
 * </p>
 *
 * @param working The id of the sender's working run, or {@link #STARTING} while it's being set up; empty if the sender is idle
 * @param queue The {@code Message-ID}s waiting, in the order they'll start
 * @param starting The email this transition took off the queue and the caller must now start
 */
record SenderState(Optional<String> working, List<String> queue, Optional<String> starting) {
	/**
	 * Stands in for a run id while a run is being set up, so no other email of the sender starts meanwhile.
	 */
	static final String STARTING = "starting";

	static final SenderState IDLE = new SenderState(Optional.empty(), List.of(), Optional.empty());

	SenderState {
		queue = List.copyOf(queue);
	}

	boolean isIdle() {
		return this.working.isEmpty() && this.queue.isEmpty();
	}

	SenderState enqueue(String messageId) {
		return new SenderState(this.working, append(this.queue, messageId), Optional.empty()).startNext();
	}

	// A run handed the email back while it's still working, so nothing starts: its release starts the queue
	SenderState requeueFirst(String messageId) {
		var reordered = new ArrayList<String>(this.queue.size() + 1);
		reordered.add(messageId);
		reordered.addAll(this.queue);

		return new SenderState(this.working, reordered, Optional.empty());
	}

	SenderState working(String runId) {
		return new SenderState(Optional.of(runId), this.queue, Optional.empty());
	}

	// A waiting review run woke for its decision: it holds the sender again, unless another run already does
	SenderState markWorking(String runId) {
		return this.working.isPresent() ? withoutStarting() : working(runId);
	}

	SenderState release(String holder) {
		var isHolder = this.working.filter(holder::equals).isPresent();

		return isHolder
			? new SenderState(Optional.empty(), this.queue, Optional.empty()).startNext()
			: withoutStarting();
	}

	private SenderState startNext() {
		var canStart = this.working.isEmpty() && !this.queue.isEmpty();

		return canStart
			? new SenderState(Optional.of(STARTING), this.queue.subList(1, this.queue.size()), Optional.of(this.queue.getFirst()))
			: this;
	}

	private SenderState withoutStarting() {
		return new SenderState(this.working, this.queue, Optional.empty());
	}

	private static List<String> append(List<String> list, String messageId) {
		var appended = new ArrayList<String>(list.size() + 1);
		appended.addAll(list);
		appended.add(messageId);

		return appended;
	}
}
