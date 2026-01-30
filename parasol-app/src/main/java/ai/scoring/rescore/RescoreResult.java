package ai.scoring.rescore;

import java.time.Instant;
import java.util.UUID;

public record RescoreResult(UUID interactionId, Instant interactionDate, Instant scoreDate, Double score) {
}
