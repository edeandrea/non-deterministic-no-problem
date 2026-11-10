package org.parasol.intake.leadership;

/**
 * Whether this application instance is the intake leader: the only one that watches the claims inbox, dispatches emails and hosts the
 * intake's runs. The others serve the UI and the REST API, and relay a reviewer's decision to the leader. Becoming the leader fires
 * {@link LeadershipAcquired}, and ceasing to be one {@link LeadershipLost}.
 */
@FunctionalInterface
public interface IntakeLeadership {
	/**
	 * Whether this application instance is the intake leader right now.
	 *
	 * @return {@code true} if it is
	 */
	boolean isLeader();
}
