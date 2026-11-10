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

import java.util.function.Consumer;
import java.util.function.UnaryOperator;

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
import io.quarkiverse.flow.dsl.FuncCallStep;
import io.quarkiverse.flow.dsl.FuncTryTaskBuilder;
import io.quarkiverse.flow.dsl.types.ContextFunction;
import io.serverlessworkflow.api.types.FlowDirectiveEnum;
import io.serverlessworkflow.api.types.Workflow;
import io.serverlessworkflow.impl.WorkflowContextData;

/**
 * The email claim intake, start to finish. It's the only place with side effects: every step is a call into a
 * package-private bean, and the agents (one step, {@code runAgents}) only decide.
 * <pre>
 * initialize → routeMatch ─ resumes a reply ───→ reply → file
 *                         ─ resumes a review ──→ waitReview
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

	// Task names are what Flow persists, what spans and Langfuse observations are named after and what the jumps below refer to: never rename one
	private static final String INITIALIZE = "initialize";
	private static final String ROUTE_MATCH = "routeMatch";
	private static final String RESOLVE_CLAIM = "resolveClaim";
	private static final String ROUTE_RESOLUTION = "routeResolution";
	private static final String RUN_AGENTS = "runAgents";
	private static final String POLICY_CHECK = "policyCheck";
	private static final String ROUTE_POLICY = "routePolicy";
	private static final String SUPERSEDE = "supersede";
	private static final String ROUTE_SUPERSEDE = "routeSupersede";
	private static final String APPLY_OUTCOME = "applyOutcome";
	private static final String REPLY = "reply";
	private static final String REPLY_NO_MATCHING_CLAIM = "replyNoMatchingClaim";
	private static final String REPLY_WHICH_CLAIM = "replyWhichClaim";
	private static final String REPLY_POLICY_INCONSISTENT = "replyPolicyInconsistent";
	private static final String FILE = "file";
	private static final String ROUTE_AFTER_FILE = "routeAfterFile";
	private static final String AWAIT_REVIEW = "awaitReview";
	private static final String WAIT_REVIEW = "waitReview";
	private static final String MARK_IN_PROCESS = "markInProcess";
	private static final String MARK_PENDING_INFORMATION = "markPendingInformation";
	private static final String APPLY_READY = "applyReady";
	private static final String RESTORE_AFTER_READY = "restoreAfterReady";
	private static final String APPLY_NEEDS_INFORMATION = "applyNeedsInformation";
	private static final String RESTORE_AFTER_NEEDS_INFORMATION = "restoreAfterNeedsInformation";
	private static final String AFTER_DECISION = "afterDecision";

	/**
	 * The task that routes a review's decision, which the dispatcher reads as the signal that a waiting run woke.
	 */
	public static final String ROUTE_DECISION = "routeDecision";

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
				// Match the email to a claim, or to the customer's pending claims
				function(INITIALIZE, this.claimMatchingSteps::initialize, IntakeRun.class, IntakeState.class),
				switchCase(ROUTE_MATCH,
					caseOf(IntakeState::isResumingReply, IntakeState.class).then(REPLY),
					caseOf(IntakeState::isResumingReview, IntakeState.class).then(AWAIT_REVIEW),
					caseOf(IntakeState::isWrongSender, IntakeState.class).then(REPLY_NO_MATCHING_CLAIM),
					caseOf(IntakeState::mayResolve, IntakeState.class).then(RESOLVE_CLAIM),
					caseDefault(RUN_AGENTS)),
				step(RESOLVE_CLAIM, this.claimMatchingSteps::resolveClaim),
				switchWhenOrElse(ROUTE_RESOLUTION, IntakeState::isWhichClaim, REPLY_WHICH_CLAIM, RUN_AGENTS, IntakeState.class),

				// Let the agents classify and extract, then check the policy
				step(RUN_AGENTS, this.agenticRoot::run),
				step(POLICY_CHECK, this.policyCheckStep::check),
				switchWhenOrElse(ROUTE_POLICY, IntakeState::isPolicyRejected, REPLY_POLICY_INCONSISTENT, SUPERSEDE, IntakeState.class),

				// Apply the outcome to the claim, and answer the customer
				step(SUPERSEDE, this.supersedeStep::supersede),
				switchWhenOrElse(ROUTE_SUPERSEDE, IntakeState::isNotRequeued, APPLY_OUTCOME, FlowDirectiveEnum.END, IntakeState.class),
				withContext(APPLY_OUTCOME, this::applyOutcome, IntakeState.class, IntakeState.class),
				replyThenFile(REPLY, this.replySteps::reply),
				replyThenFile(REPLY_NO_MATCHING_CLAIM, this.replySteps::replyNoMatchingClaim),
				replyThenFile(REPLY_WHICH_CLAIM, this.replySteps::replyWhichClaim),
				replyThenFile(REPLY_POLICY_INCONSISTENT, this.replySteps::replyPolicyInconsistent),
				step(FILE, this.fileEmailStep::file),
				switchWhenOrElse(ROUTE_AFTER_FILE, IntakeState::needsReview, AWAIT_REVIEW, FlowDirectiveEnum.END, IntakeState.class),

				// Wait for the reviewer's decision, and apply it
				// Records that this run is about to wait, for any application instance to read: Flow's own status isn't visible outside the application instance that hosts the run
				withContext(AWAIT_REVIEW, this::awaitReview, Object.class, Object.class),
				listen(WAIT_REVIEW, toOne(consumed(ReviewDecisionPublisher.REVIEW_DECIDED).dataAs(ReviewDecided.class, this::isForThisRun)).first()),
				switchWhenOrElse(ROUTE_DECISION, ReviewDecided::isReviewReady, MARK_IN_PROCESS, MARK_PENDING_INFORMATION, ReviewDecided.class),

				// A fault in the decision step puts the claim back in review, and afterDecision sends the same run back to its wait
				tryCatch(MARK_IN_PROCESS, decision(APPLY_READY, this::markInProcess, RESTORE_AFTER_READY)
					.andThen(attempt -> attempt.then(AFTER_DECISION))),
				tryCatch(MARK_PENDING_INFORMATION, decision(APPLY_NEEDS_INFORMATION, this::markPendingInformation, RESTORE_AFTER_NEEDS_INFORMATION)),
				switchWhenOrElse(AFTER_DECISION, ClaimIntakeFlow::shouldWaitAgain, AWAIT_REVIEW, FlowDirectiveEnum.END, AfterDecision.class)))
			.build();
	}

	private static FuncCallStep<IntakeState, IntakeState> step(String name, UnaryOperator<IntakeState> function) {
		return function(name, function, IntakeState.class, IntakeState.class);
	}

	private static FuncCallStep<IntakeState, IntakeState> replyThenFile(String name, UnaryOperator<IntakeState> function) {
		return step(name, function).then(FILE);
	}

	// Applies the decision, and restores the review if applying it faults before its commit (the steps raise DECISION_FAILED for that)
	private Consumer<FuncTryTaskBuilder> decision(String applyName, ContextFunction<ReviewDecided, AfterDecision> apply, String restoreName) {
		return attempt -> attempt
			.tryCatch(tasks(withContext(applyName, apply, ReviewDecided.class, AfterDecision.class)))
			.catchType(ReviewDecisionSteps.DECISION_FAILED, tasks(withContext(restoreName, this::restoreReview, Object.class, AfterDecision.class)));
	}

	private IntakeState applyOutcome(IntakeState state, WorkflowContextData context) {
		return this.applyOutcomeStep.apply(state, context.instanceData().id(), conversationId(context));
	}

	private Object awaitReview(Object input, WorkflowContextData context) {
		this.reviewDecisionSteps.awaitReview(context.instanceData().id());

		return input;
	}

	private AfterDecision restoreReview(Object ignored, WorkflowContextData context) {
		return this.reviewDecisionSteps.restoreReview(context.instanceData().id());
	}

	private AfterDecision markInProcess(ReviewDecided decided, WorkflowContextData context) {
		return this.reviewDecisionSteps.markInProcess(decided, runOf(context), context.instanceData().id());
	}

	private AfterDecision markPendingInformation(ReviewDecided decided, WorkflowContextData context) {
		return this.reviewDecisionSteps.markPendingInformation(decided, runOf(context), context.instanceData().id());
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
