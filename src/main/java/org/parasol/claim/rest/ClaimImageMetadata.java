package org.parasol.claim.rest;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import org.parasol.claim.model.ClaimImageKind;

/** Image metadata returned by the claim image listing endpoint. */
@JsonNaming(SnakeCaseStrategy.class)
public record ClaimImageMetadata(long id, ClaimImageKind kind, String fileName, String contentType, String url) {
}
