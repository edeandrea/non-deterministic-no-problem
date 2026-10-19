package ai.scoring.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConversationContextTests {
	private final ConversationContext context = new ConversationContext();

	@Test
	void thereIsNoIdOutsideAConversation() {
		assertThat(this.context.currentId()).isEmpty();
	}

	@Test
	void anEnteredIdIsCurrentUntilTheScopeCloses() {
		try (var ignored = this.context.enter("conversation-1")) {
			assertThat(this.context.currentId()).contains("conversation-1");
		}

		assertThat(this.context.currentId()).isEmpty();
	}

	@Test
	void callInReturnsTheResultAndRestoresTheContext() {
		var seen = this.context.callIn("conversation-2", () -> this.context.currentId().orElseThrow());

		assertThat(seen).isEqualTo("conversation-2");
		assertThat(this.context.currentId()).isEmpty();
	}

	@Test
	void runInSeesTheIdAndNestedConversationsRestoreTheOuterOne() {
		this.context.runIn("outer", () -> {
			this.context.runIn("inner", () -> assertThat(this.context.currentId()).contains("inner"));

			assertThat(this.context.currentId()).contains("outer");
		});
	}

	@Test
	void aFailureRestoresTheContextAndKeepsItsType() {
		assertThatThrownBy(() -> this.context.callIn("failing", () -> {
			throw new IllegalArgumentException("boom");
		})).isInstanceOf(IllegalArgumentException.class).hasMessage("boom");
		assertThatThrownBy(() -> this.context.callIn("checked", () -> {
			throw new Exception("checked");
		})).isInstanceOf(IllegalStateException.class).hasRootCauseMessage("checked");
		assertThat(this.context.currentId()).isEmpty();
	}
}
