package ai.scoring.langfuse.session;

import com.langfuse.api.model.ObservationV2;

public record ConversationExchange(String traceName, String traceId, String input, String output) {
  public static ConversationExchange from(ObservationV2 observation) {
    return new ConversationExchange(observation.getName(), observation.getTraceId(), String.valueOf(observation.getInput()), String.valueOf(observation.getOutput()));
  }
}
