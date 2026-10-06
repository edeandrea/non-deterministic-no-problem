package org.parasol.notification.guardrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.parasol.notification.ai.GenerateEmailService;
import org.parasol.notification.model.ClaimInfo;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.guardrail.ChatExecutor;
import dev.langchain4j.guardrail.GuardrailRequestParams;
import dev.langchain4j.guardrail.OutputGuardrailExecutor;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Drives the email guardrails through a real {@link OutputGuardrailExecutor}, the way
 * {@link GenerateEmailService} does, rather than calling {@code validate} directly.
 * <p>
 * The per-guardrail tests all call {@code validate} directly, which bypasses the executor entirely. That is why
 * <a href="https://github.com/edeandrea/non-deterministic-no-problem/issues/228">#228</a> shipped green: the
 * executor refuses a reprompt once any guardrail in the chain has rewritten the output, so only the first guardrail
 * could ever reprompt. Nothing that calls {@code validate} directly can see that.
 */
@QuarkusTest
class EmailOutputGuardrailChainTests {
	private static final String CLIENT_NAME = "Marty McFly";
	private static final String CLAIM_NUMBER = "CLM01000000";
	private static final String CLAIM_STATUS = "denied";
	private static final ClaimInfo CLAIM_INFO = new ClaimInfo(CLIENT_NAME, CLAIM_NUMBER, CLAIM_STATUS);
	private static final String JSON = "{\"subject\":\"Claim Status Update\",\"body\":\"%s\"}";
	private static final String BODY = """
		Dear %s,

		Your claim %s has been %s.""".formatted(CLIENT_NAME, CLAIM_NUMBER, CLAIM_STATUS);

	@Inject
	EmailContainsRequiredInformationOutputGuardrail containsRequiredInformation;

	@Inject
	EmailStartsAppropriatelyOutputGuardrail startsAppropriately;

	@Inject
	EmailEndsAppropriatelyOutputGuardrail endsAppropriately;

	@Inject
	PolitenessOutputGuardrail politeness;

	@InjectMock
	PolitenessService politenessService;

	@BeforeEach
	void beforeEach() {
		when(this.politenessService.isPolite(anyString()))
			.thenReturn(true);
	}

	/**
	 * The email passes the first two guardrails and fails the third, which is exactly the shape that threw
	 * {@code "Retry or reprompt is not allowed after a rewritten output"} before #228 was fixed.
	 */
	@Test
	void aGuardrailAfterTheFirstCanStillReprompt() {
		// Fails EmailEndsAppropriately only: correct greeting and claim details, but no closing block
		var missingEnding = emailWith(BODY);
		var corrected = emailWith(BODY + GenerateEmailService.EMAIL_ENDING);
		var modelCalls = new AtomicInteger();
		var chatExecutor = new FixedChatExecutor(corrected, modelCalls);

		var result = executor().execute(requestFor(missingEnding, chatExecutor));

		assertThat(result.isSuccess())
			.as("The reprompt should have produced a second attempt that passes every guardrail")
			.isTrue();

		assertThat(modelCalls)
			.as("The model should have been re-prompted exactly once")
			.hasValue(1);
	}

	/**
	 * The last guardrail in the chain is the most constrained one: three rewriting guardrails run before it.
	 */
	@Test
	void theLastGuardrailInTheChainCanStillReprompt() {
		var impolite = emailWith(BODY + GenerateEmailService.EMAIL_ENDING);
		var corrected = emailWith(BODY + GenerateEmailService.EMAIL_ENDING);
		var modelCalls = new AtomicInteger();
		var chatExecutor = new FixedChatExecutor(corrected, modelCalls);

		// Impolite on the first pass, polite on the re-prompted one
		when(this.politenessService.isPolite(anyString()))
			.thenReturn(false, true);

		var result = executor().execute(requestFor(impolite, chatExecutor));

		assertThat(result.isSuccess())
			.as("PolitenessOutputGuardrail runs last, so three guardrails have already succeeded before it reprompts")
			.isTrue();

		assertThat(modelCalls)
			.as("The model should have been re-prompted exactly once")
			.hasValue(1);
	}

	/**
	 * None of these guardrails may report success by rewriting: the first one that does silently disables
	 * reprompting for every guardrail after it.
	 */
	@Test
	void noGuardrailRewritesTheOutput() {
		var valid = emailWith(BODY + GenerateEmailService.EMAIL_ENDING);
		var request = requestFor(valid, new FixedChatExecutor(valid, new AtomicInteger()));

		assertThat(List.of(this.containsRequiredInformation, this.startsAppropriately, this.endsAppropriately, this.politeness))
			.allSatisfy(guardrail ->
				assertThat(guardrail.validate(request).hasRewrittenResult())
					.as("%s must not rewrite the output", guardrail.getClass().getSimpleName())
					.isFalse()
			);
	}

	private OutputGuardrailExecutor executor() {
		// Same guardrails, same order, as GenerateEmailService.generateEmail
		return OutputGuardrailExecutor.builder()
			.guardrails(this.containsRequiredInformation, this.startsAppropriately, this.endsAppropriately, this.politeness)
			.build();
	}

	private static AiMessage emailWith(String body) {
		return AiMessage.from(JSON.formatted(body).replaceAll("\n", "\\\\n"));
	}

	private static OutputGuardrailRequest requestFor(AiMessage responseFromLLM, ChatExecutor chatExecutor) {
		return OutputGuardrailRequest.builder()
			.responseFromLLM(ChatResponse.builder().aiMessage(responseFromLLM).build())
			.requestParams(
				GuardrailRequestParams.builder()
					.userMessageTemplate("")
					.variables(Map.of("claimInfo", CLAIM_INFO))
					// The executor fires an observability event per guardrail, which needs a context
					.invocationContext(
						InvocationContext.builder()
							.invocationId(UUID.randomUUID())
							.interfaceName(GenerateEmailService.class.getSimpleName())
							.methodName("generateEmail")
							.methodArguments(List.of(CLAIM_INFO))
							.chatMemoryId("test")
							.invocationParameters(new InvocationParameters())
							.timestampNow()
							.build()
					)
					.build()
			)
			.chatExecutor(chatExecutor)
			.build();
	}

	/**
	 * Stands in for the model on a reprompt: always answers with the same corrected email, and counts the calls so a
	 * test can tell a real retry from a guardrail that never got to ask for one.
	 */
	private record FixedChatExecutor(AiMessage response, AtomicInteger calls) implements ChatExecutor {
		@Override
		public ChatResponse execute() {
			return execute(List.of());
		}

		@Override
		public ChatResponse execute(List<ChatMessage> chatMessages) {
			this.calls.incrementAndGet();

			return ChatResponse.builder()
				.aiMessage(this.response)
				.build();
		}
	}
}
