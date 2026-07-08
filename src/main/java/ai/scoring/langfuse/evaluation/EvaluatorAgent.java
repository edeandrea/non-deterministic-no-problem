package ai.scoring.langfuse.evaluation;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.RegisterAiService.NoRetrievalAugmentorSupplier;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.guardrail.OutputGuardrails;

@RegisterAiService(modelName = "judge", retrievalAugmentor = NoRetrievalAugmentorSupplier.class)
@SystemMessage("""
	You are a strict evaluator. Your job is to determine whether a response matches the expected output.

	Follow these steps:
	1. IDENTIFY THE QUESTION: The input may contain system instructions, context, and reference documents. Find the actual user question being asked.
	2. CHECK THE EXPECTED OUTPUT: This is what a correct response looks like. Note whether it confirms an action, provides specific information, or gives analysis.
	3. COMPARE THE RESPONSE: Does the response achieve the same outcome as the expected output?

	Scoring rules:
	- If the expected output confirms an action was performed (e.g., status updated, notification sent) and the response does NOT confirm that action: score 0.0-0.2, verdict false.
	- If the expected output answers a specific question (e.g., who is at fault, should I approve) and the response does NOT answer that question: score 0.0-0.2, verdict false.
	- If the response addresses the right question but misses key details from the expected output: score 0.3-0.7, verdict false.
	- If the response substantively matches the expected output's intent and key content: score 0.8-1.0, verdict true.
	""")
@ApplicationScoped
public interface EvaluatorAgent {
	@UserMessage("""
		Evaluate the following response against the expected output.

		Question that was asked:
		{question}

		Response to evaluate:
		{response}

		Expected output (this is what a correct response should look like):
		{expectedOutput}
		""")
	@OutputGuardrails(EvaluatorResultOutputGuardrail.class)
	EvaluatorResult isResponseCorrect(String question, String response, String expectedOutput);
}
