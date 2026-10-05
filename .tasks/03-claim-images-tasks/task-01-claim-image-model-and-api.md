# Task 01: Claim Image Model and REST API

**Type:** Code Modification

## Goal

Store claim images in PostgreSQL and serve them over REST, with tests.

## What to Do

- Create a `ClaimImage` entity:
  - many-to-one to `Claim` by `id`
  - `kind` enum: `ORIGINAL` (customer photo) and `PROCESSED` (annotated damage image)
  - `fileName`, `contentType`
  - `data` as PostgreSQL `bytea`. Confirm Hibernate maps `byte[]` to `bytea` and not `oid`; avoid `@Lob`.
  - `createdAt` (`Instant`)
- Add REST endpoints (Jakarta REST annotations, plural kebab-case paths):
  - `GET /api/db/claims/{id}/images`: a JSON list of image metadata (id, kind, file name, content type, URL), ordered by `createdAt` then `id`. No binary data.
  - Image ids are table-wide (the `PanacheEntity` id; decided with the user). Per-claim scoping comes from the lookup: always query by claim id **and** image id together.
  - `GET /api/db/claims/{id}/images/{imageId}`: the raw bytes, with the stored content type.
  - Errors use RFC 9457 Problem Details:
    - 404 for an unknown claim, or an image that doesn't belong to the claim
    - `200` with an empty list for a claim with no images
- Expose the image metadata as a record DTO; don't return the entity.
- Add a small service method to store an image for a claim. Issue 5's intake reuses it.
- **Tests:**
  - list for a claim with images and without
  - fetch bytes with the correct content type
  - 404 for an unknown claim, an unknown image, and an image belonging to another claim
  - store-then-fetch round trip

## Files/Areas

- `src/main/java/org/parasol/claim/model/ClaimImage.java`, `ClaimImageKind.java` (new)
- `src/main/java/org/parasol/claim/rest/ClaimImageResource.java` (new), or new methods on `ClaimResource`
- `src/test/java/org/parasol/claim/rest/` (new tests)

## Key Points

- `ClaimResource` today returns entities directly (`Claim.listAll()`). Don't change that contract in
  this issue. Only the new image endpoints use DTOs.
- Problem Details: the API currently has no error handling. Check whether Quarkus REST ships an RFC
  9457 mapper in the version from issue 1; otherwise add a minimal `ExceptionMapper` producing `application/problem+json`.
- Tests that create images or claims must delete them afterwards (`ClaimsListPageTests` expects exactly 6 claims).

## Done When

- [ ] Both endpoints exist, and the OpenAPI document (`/q/openapi`) lists them.
- [ ] Tests for every case listed above pass.
- [ ] `./mvnw -B clean test-compile -Pollama` succeeds.