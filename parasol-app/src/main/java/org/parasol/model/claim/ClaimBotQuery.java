package org.parasol.model.claim;

import java.time.LocalDate;

public record ClaimBotQuery(long claimId, String claim, String query, LocalDate inceptionDate) {

}
