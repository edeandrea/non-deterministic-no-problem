package org.parasol.claim.rest;

import org.parasol.claim.model.ClaimImageKind;

import com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Image metadata returned by {@code GET /api/db/claims/{id}/images}. It never carries the image bytes.
 *
 * @param id the image id
 * @param kind {@code ORIGINAL} or {@code PROCESSED}
 * @param fileName the original file name
 * @param contentType the media type the bytes are served with, e.g. {@code image/jpeg}
 * @param url the root-relative path of the image bytes, e.g. {@code /api/db/claims/1/images/5}. It's relative so it
 *            keeps the scheme and host the client used, which matters behind a TLS-terminating proxy.
 */
@JsonNaming(SnakeCaseStrategy.class)
public record ClaimImageMetadata(long id, ClaimImageKind kind, String fileName, String contentType, String url) {
}