# Task 04: Documentation and Verification

**Type:** Verification

## Goal

The docs describe backend-served images, and an independent review confirms code, tests and docs agree.

## What to Do

- Update the documentation:
  - **`CLAUDE.md`:** in Architecture, mention `ClaimImage` and the image endpoints. In REST, change "the only
    REST endpoints are …" to list them. In Gotchas, explain that the seeder looks the sample claims up by their
    explicit ids (not claim numbers, which are generated) and runs on every start, re-inserting missing images.
  - **`README.md`:** the statement that the only REST endpoints are `GET /api/db/claims` and `GET /api/db/claims/{id}`.
  - **`src/main/webui/README.md`:** any mention of image assets or `OriginalApp`.
  - **`docs/*.puml`:** if the REST surface or the frontend appears, update and re-render with `./docs/render-diagrams.sh`.
- Run `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`, and the image and UI test suites under `-Pollama`.
- Have a **fresh** reviewer check:
  - the endpoints match the Problem Details convention
  - no frontend path building remains
  - the docs match the code

  Re-review after fixes.
- Give the user a manual check: open every seeded claim and check both image panels; then start the app twice and confirm images aren't duplicated.

## Files/Areas

- `CLAUDE.md`, `README.md`, `src/main/webui/README.md`, `docs/`

## Key Points

- Verify each doc statement against the code. Leave `AGENTS.md` and `conversation-export.md` untouched.

## Done When

- [x] No document claims the claims endpoints are the only REST endpoints, or that images are bundled frontend assets.
- [x] Both `test-compile` runs succeed, and the image/UI suites pass (or fail only for documented secret reasons).
- [x] The fresh review has no unresolved BLOCKER or MAJOR findings.
- [x] **Maintainer:** real-key `./mvnw verify` (default profile). Passed (maintainer, 2026-10-05).
- [ ] **Maintainer:** manual check: every seeded claim's images in both panels; restart and confirm no duplicates.
