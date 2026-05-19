package ai.scoring.langfuse.session;

import java.util.List;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.RegisterAiService.NoRetrievalAugmentorSupplier;

@RegisterAiService(modelName = "session-sentiment", retrievalAugmentor = NoRetrievalAugmentorSupplier.class)
public interface SessionSentimentService {

	@SystemMessage("""
		You are an AI conversation analyst. Your task is to evaluate the overall sentiment of a conversation.

		Classify the sentiment as one of: POSITIVE, NEUTRAL, or NEGATIVE.

		Guidelines:

		POSITIVE — The user's queries were answered satisfactorily. Examples:
		- User asks about their claim coverage, gets a clear answer, and thanks the assistant
		- User requests a status update, assistant provides it and offers to help further, user says "that's all I needed"
		- User asks multiple questions, each answered satisfactorily, conversation ends naturally

		NEUTRAL — The exchange was transactional with no strong emotional signals. Examples:
		- User asks a single factual question, gets a short answer, disconnects without reaction
		- User gets an answer but doesn't engage further — neither satisfied nor dissatisfied
		- Brief transactional exchange with no emotional signals either way

		NEGATIVE — The user appeared frustrated, confused, or unsatisfied. Examples:
		- User asks the same question multiple times, rephrasing because the AI keeps misunderstanding
		- User expresses frustration ("that's not what I asked", "this isn't helpful", "you already said that")
		- User asks about something outside the AI's scope, AI can't help, user gives up
		- User corrects the AI repeatedly or pushes back on incorrect information

		Provide your assessment as a "sentiment" (POSITIVE, NEUTRAL, or NEGATIVE) and "reasoning" (one sentence explaining the assessment).
		""")
	@UserMessage("""
		Analyze the following conversation:

		{#for exchange in exchanges}
		----------------------------
		Question: {exchange.input}
		
		----------------------------
		Answer: {exchange.output}

		{/for}
		""")
	SessionSentiment evaluate(List<ConversationExchange> exchanges);
}
