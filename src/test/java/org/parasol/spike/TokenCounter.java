package org.parasol.spike;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Singleton;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;

/**
 * #221 spike: counts the LLM calls and tokens of the harness's (sequential) agent calls.
 */
@Singleton
public class TokenCounter implements ChatModelListener {
	private final AtomicInteger calls = new AtomicInteger();
	private final AtomicInteger inputTokens = new AtomicInteger();
	private final AtomicInteger outputTokens = new AtomicInteger();

	@Override
	public void onResponse(ChatModelResponseContext context) {
		this.calls.incrementAndGet();
		var usage = context.chatResponse().tokenUsage();
		if (usage != null) {
			this.inputTokens.addAndGet(orZero(usage.inputTokenCount()));
			this.outputTokens.addAndGet(orZero(usage.outputTokenCount()));
		}
	}

	void reset() {
		this.calls.set(0);
		this.inputTokens.set(0);
		this.outputTokens.set(0);
	}

	int calls() {
		return this.calls.get();
	}

	TokenUsage usage() {
		return new TokenUsage(this.inputTokens.get(), this.outputTokens.get());
	}

	private static int orZero(Integer value) {
		return (value == null) ? 0 : value;
	}
}
