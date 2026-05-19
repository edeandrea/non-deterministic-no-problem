package ai.scoring.langfuse.session;

import ai.scoring.langfuse.rest.model.Trace;

public record ConversationExchange(String traceName, String traceId, String input, String output) {
  public static ConversationExchange from(Trace trace) {
    return new ConversationExchange(trace.getName(), trace.getId(),String.valueOf(trace.getInput()), String.valueOf(trace.getOutput()));
  }
}
