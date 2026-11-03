package org.parasol.intake.observability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;

import io.quarkus.logging.Log;

/**
 * Where the intake publishes what it did, as {@link IntakeActivity} events. Telemetry must never change what the intake does,
 * so an observer that throws is logged and never reaches the code that published: an accepted send stays accepted, and the
 * failure path still files the email. The observers run on the publishing thread, in its transaction and span: the status and
 * decision observers rely on that.
 */
@ApplicationScoped
public class IntakeActivities {
	private final Event<IntakeActivity> activities;

	IntakeActivities(Event<IntakeActivity> activities) {
		this.activities = activities;
	}

	/**
	 * Publishes an activity to its observers.
	 *
	 * @param activity What the intake did
	 */
	public void publish(IntakeActivity activity) {
		try {
			this.activities.fire(activity);
		}
		catch (Exception e) {
			Log.warnf(e, "An observer of %s failed", activity.getClass().getSimpleName());
		}
	}
}
