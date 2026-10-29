package org.parasol.intake.workflow;

import static io.quarkiverse.flow.dsl.FlowDSL.caseDefault;
import static io.quarkiverse.flow.dsl.FlowDSL.caseOf;
import static io.quarkiverse.flow.dsl.FlowDSL.consumed;
import static io.quarkiverse.flow.dsl.FlowDSL.function;
import static io.quarkiverse.flow.dsl.FlowDSL.listen;
import static io.quarkiverse.flow.dsl.FlowDSL.switchCase;
import static io.quarkiverse.flow.dsl.FlowDSL.switchWhenOrElse;
import static io.quarkiverse.flow.dsl.FlowDSL.tasks;
import static io.quarkiverse.flow.dsl.FlowDSL.toOne;
import static io.quarkiverse.flow.dsl.FlowDSL.tryCatch;
import static io.quarkiverse.flow.dsl.FlowDSL.withContext;
import static io.quarkiverse.flow.dsl.FlowWorkflowBuilder.workflow;

import jakarta.enterprise.context.ApplicationScoped;

import org.parasol.intake.review.ReviewDecided;
import org.parasol.intake.review.ReviewDecisionPublisher;
import org.parasol.intake.review.ReviewOutcome;
import org.parasol.intake.workflow.mail.FileEmailStep;
import org.parasol.intake.workflow.mail.ReplySteps;
import org.parasol.intake.workflow.outcome.ApplyOutcomeStep;
import org.parasol.intake.workflow.policy.PolicyCheckStep;
import org.parasol.intake.workflow.review.AfterDecision;
import org.parasol.intake.workflow.review.ReviewDecisionSteps;
import org.parasol.intake.workflow.review.SupersedeStep;

import io.quarkiverse.flow.Flow;
import io.serverlessworkflow.api.types.FlowDirectiveEnum;
import io.serverlessworkflow.api.types.Workflow;
import io.serverlessworkflow.impl.WorkflowContextData;

/**
 * The email claim intake, start to finish. It's the only place with side effects: every step is a call into a
 * package-private bean, and the agents (one step, {@code runAgents}) only decide.
 * <pre>
 * initialize → routeMatch ─ resumes a review ──→ waitReview
 *                         ─ wrong sender ────────→ replyNoMatchingClaim → file
 *                         ─ may resolve ─→ resolveClaim ─ unsure → replyWhichClaim → file
 *            → runAgents → policyCheck ─ rejected → replyPolicyInconsistent → file
 *            → supersede ─ claim being decided → END (the email goes back in the queue)
 *            → applyOutcome → reply → file
 * file → needs review? ─ no → END
 *                       └ yes → waitReview → routeDecision → markInProcess | markPendingInformation → END
 * </pre>
 * A fault in a decision step puts the claim back in review and the same run waits again. A run that faults any other way
 * while it owns a claim's review is replaced by one that only waits ({@link IntakeRun#resumesReview()}), so the review is
 * never left without a run.
 * <p>
 *   The run's data is ids only; steps re-read the email from the processing folder.
 * </p>
 */
@ApplicationScoped
public class ClaimIntakeFlow extends Flow {
	/**
	 * The workflow name, as Flow persists it.
	 */
	public static final String NAME = "claim-intake";

	private final ClaimMatchingSteps claimMatchingSteps;
	private final AgentStep agenticRoot;
	private final PolicyCheckStep policyCheckStep;
	private final SupersedeStep supersedeStep;
	private final ApplyOutcomeStep applyOutcomeStep;
	private final ReplySteps replySteps;
	private final FileEmailStep fileEmailStep;
	private final ReviewDecisionSteps reviewDecisionSteps;

	ClaimIntakeFlow(
		ClaimMatchingSteps claimMatchingSteps,
		AgentStep agenticRoot,
		PolicyCheckStep policyCheckStep,
		SupersedeStep supersedeStep,
		ApplyOutcomeStep applyOutcomeStep,
		ReplySteps replySteps,
		FileEmailStep fileEmailStep,
		ReviewDecisionSteps reviewDecisionSteps) {

		this.claimMatchingSteps = claimMatchingSteps;
		this.agenticRoot = agenticRoot;
		this.policyCheckStep = policyCheckStep;
		this.supersedeStep = supersedeStep;
		this.applyOutcomeStep = applyOutcomeStep;
		this.replySteps = replySteps;
		this.fileEmailStep = fileEmailStep;
		this.reviewDecisionSteps = reviewDecisionSteps;
	}

	@Override
	public Workflow descriptor() {
		return workflow(NAME)
			.tasks(tasks(
				function("initialize", this.claimMatchingSteps::initialize, IntakeRun.class, IntakeState.class),
				switchCase("routeMatch",
					caseOf(IntakeState::needsReview, IntakeState.class).then("waitReview"),
					caseOf(IntakeState::isWrongSender, IntakeState.class).then("replyNoMatchingClaim"),
					caseOf(IntakeState::mayResolve, IntakeState.class).then("resolveClaim"),
					caseDefault("runAgents")),
				function("resolveClaim", this.claimMatchingSteps::resolveClaim, IntakeState.class, IntakeState.class),
				switchWhenOrElse("routeResolution", IntakeState::isWhichClaim, "replyWhichClaim", "runAgents", IntakeState.class),
				function("runAgents", this.agenticRoot::run, IntakeState.class, IntakeState.class),
				function("policyCheck", this.policyCheckStep::check, IntakeState.class, IntakeState.class),
				switchWhenOrElse("routePolicy", IntakeState::isPolicyRejected, "replyPolicyInconsistent", "supersede", IntakeState.class),
				function("supersede", this.supersedeStep::supersede, IntakeState.class, IntakeState.class),
				switchWhenOrElse("routeSupersede", IntakeState::isNotRequeued, "applyOutcome", FlowDirectiveEnum.END, IntakeState.class),
				withContext("applyOutcome", (state, context) ->
					this.applyOutcomeStep.apply(state, context.instanceData().id(), conversationId(context)), IntakeState.class, IntakeState.class),
				function("reply", this.replySteps::reply, IntakeState.class, IntakeState.class)
					.then("file"),
				function("replyNoMatchingClaim", this.replySteps::replyNoMatchingClaim, IntakeState.class, IntakeState.class)
					.then("file"),
				function("replyWhichClaim", this.replySteps::replyWhichClaim, IntakeState.class, IntakeState.class)
					.then("file"),
				function("replyPolicyInconsistent", this.replySteps::replyPolicyInconsistent, IntakeState.class, IntakeState.class)
					.then("file"),
				function("file", this.fileEmailStep::file, IntakeState.class, IntakeState.class),
				switchWhenOrElse("routeAfterFile", IntakeState::needsReview, "waitReview", FlowDirectiveEnum.END, IntakeState.class),
				listen("waitReview", toOne(consumed(ReviewDecisionPublisher.REVIEW_DECIDED).dataAs(ReviewDecided.class, this::isForThisRun)).first()),
				switchWhenOrElse(
					"routeDecision",
					ReviewDecided::isReviewReady,
					"markInProcess",
					"markPendingInformation",
					ReviewDecided.class
				),
				tryCatch("markInProcess", attempt -> attempt
					.tryCatch(tasks(withContext("applyReady", (decided, context) ->
						this.reviewDecisionSteps.markInProcess(decided, runOf(context), context.instanceData().id()), ReviewDecided.class, AfterDecision.class)))
					.catchType(ReviewDecisionSteps.DECISION_FAILED, tasks(withContext("restoreAfterReady", (_, context) ->
						this.reviewDecisionSteps.restoreReview(context.instanceData().id()), Object.class, AfterDecision.class)))
					.then("afterDecision")),
				tryCatch("markPendingInformation", attempt -> attempt
					.tryCatch(tasks(withContext("applyNeedsInformation", (decided, context) ->
						this.reviewDecisionSteps.markPendingInformation(decided, runOf(context), context.instanceData().id()), ReviewDecided.class, AfterDecision.class)))
					.catchType(ReviewDecisionSteps.DECISION_FAILED, tasks(withContext("restoreAfterNeedsInformation", (_, context) ->
						this.reviewDecisionSteps.restoreReview(context.instanceData().id()), Object.class, AfterDecision.class)))),
				switchWhenOrElse("afterDecision", ClaimIntakeFlow::shouldWaitAgain, "waitReview", FlowDirectiveEnum.END, AfterDecision.class)))
			.build();
	}

	private static boolean shouldWaitAgain(AfterDecision next) {
		return next == AfterDecision.WAIT_AGAIN;
	}

	private static IntakeRun runOf(WorkflowContextData context) {
		return context.instanceData()
			.input()
			.as(IntakeRun.class)
			.orElseThrow();
	}

	private static String conversationId(WorkflowContextData context) {
		return runOf(context).conversationId();
	}

	// Only the run the reviewer's decision names reacts to it, and only if that run is the claim's reviewer-claimed one
	private boolean isForThisRun(ReviewDecided decided, WorkflowContextData context) {
		var runId = context.instanceData().id();

		return runId.equals(decided.runId()) && this.reviewDecisionSteps.isDecisionFor(decided.claimId(), runId);
	}
}
