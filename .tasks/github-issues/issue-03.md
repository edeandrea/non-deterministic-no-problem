TITLE: Serve claim images from the backend instead of bundled frontend assets
## Summary

Claim images are currently static frontend files. `ClaimDetail.tsx` builds `original_car${id}.jpg` /
`car${id}-processed.jpg` from the claim **id**. Those files only reach the build because the legacy
`OriginalApp.tsx` page imports them, so any claim with id > 6 shows broken images, and no test covers
images. Store the images in PostgreSQL and serve them over REST. Email intake ({{ISSUE_5}}) also needs
this to store customer photos.

**Depends on** {{ISSUE_2}}: both issues change the claim model and `ClaimDetail.tsx`.

## Changes

- **`ClaimImage` entity:**
  - foreign key to `Claim.id`
  - `kind`: `ORIGINAL` (customer photo) or `PROCESSED` (annotated damage image)
  - file name, content type
  - `bytea` data (avoid `@Lob` / `oid`)
  - `createdAt`
- **REST** (Jakarta REST annotations, plural kebab-case paths):
  - `GET /api/db/claims/{id}/images` returns a metadata list (record DTO, no binary data). A claim with no images gets an empty list.
  - `GET /api/db/claims/{id}/images/{imageId}` returns the raw bytes with the stored content type.
  - **RFC 9457 Problem Details:** 404 for an unknown claim, an unknown image, or an image that belongs to another claim.
- **Service method** to store an image for a claim ({{ISSUE_5}} reuses it).
- **Startup seeder:**
  - Moves the 12 images used by the six seeded claims into backend resources.
  - On every start, inserts any of them that are missing, looking claims up by their **explicit ids (1–6) from
    `import.sql`**. Claim numbers are generated, so they can't be listed in the seeder.
  - Idempotent. Logs and skips a missing claim instead of failing startup. This matters because `%prod`/`%openshift` recreate the schema on every start.
- **UI:**
  - `ClaimDetail.tsx` fetches the image list.
  - The Documents tab shows `ORIGINAL` images.
  - The right-hand panel shows `PROCESSED` images, otherwise `ORIGINAL`, otherwise "No images attached".
  - `ImageCarousel.tsx` takes the URLs from the API.
- **Cleanup:**
  - Remove `OriginalApp.tsx` and its `/OriginalApp` route.
  - Remove assets nothing references any more: `original_car0`, `car0-processed`, `car1-3.jpg`,
    `new_car1-3.png`, `sample.png`, `bgimages/car1.jpg`. Confirm there are no references before deleting.
  - Keep the favicon working.

## Decisions

- Images are stored in the database. No object storage, no filesystem.
- New claims never get "processed" images.
- The existing `ClaimResource` contract (it returns entities) is unchanged in this issue.

## Tests

- **API:**
  - listing for a claim with images and without
  - fetching bytes with the correct content type
  - each 404 case
  - store, then fetch
- **Seeder:** seeds 12 images; is idempotent; re-inserts a missing image; skips a missing claim without failing.
- **Playwright:**
  - a seeded claim shows both images, served from the API
  - a claim without images shows "No images attached"
  - `/OriginalApp` is gone
- Tests clean up the claims and images they create.

## Documentation

- `CLAUDE.md`: `ClaimImage`, the endpoints, and seeder gotchas.
- `README.md`: the statement that the claims endpoints are the only REST endpoints.
- `src/main/webui/README.md`, and diagrams if relevant.

## Tasks

- [ ] Claim image model and REST API, with tests
- [ ] Seed the existing claim images, with tests
- [ ] UI reads images from the API; remove `OriginalApp`; Playwright tests
- [ ] **Documentation and verification:** an independent review, then a manual check by the maintainer
  (every seeded claim's images; no duplicates after a restart)