# Issue 3: Claim Images from the Backend — Task Execution Plan

## Your Mission

Move claim images from bundled frontend assets into PostgreSQL, serve them through REST, have the UI
read them from the API, and remove the legacy `OriginalApp` page that images currently depend on.
Third of five issues; see `.tasks/claim-intake-roadmap.md`.

**Plan File:** `.tasks/03-gh214-claim-images/PLAN.md`
**Tasks Directory:** `.tasks/03-gh214-claim-images/`

## Execution Steps

### 1. Read This Plan
Find the next incomplete task, and read the key decisions and notes left by earlier agents.

### 2. Understand Your Task
Read your task file in `.tasks/03-gh214-claim-images/task-XX-*.md`: Goal, Key Points, Done When.

### 3. Execute the Task
- Follow the global rules in `AGENTS.md`.
- Make sure the code compiles: `./mvnw -B clean test-compile -Pollama` and `./mvnw -B clean test-compile`.
- **Update every affected document in the same task.**
- Check every Done When item.

### 4. Update This Plan
Mark the task complete, add a 1–2 sentence outcome under Shared Context, and record decisions that affect later tasks.

### 5. Await Approval (MANDATORY)
Wait for the user's confirmation before moving on.

### 6. Review Task List (MANDATORY)
Re-assess the remaining tasks: split, merge, remove, reorder or add any?

### 7. Present Review Findings (MANDATORY)
Present findings even if nothing needs to change, and wait for approval.

### 8. Update Task Files (if approved)
Edit or create task files and update `## Task Plan`.

---

## Task Plan

- [x] [task-01-claim-image-model-and-api.md](task-01-claim-image-model-and-api.md): Claim image model and REST API
- [x] [task-02-seed-images.md](task-02-seed-images.md): Seed the existing claim images
- [x] [task-03-ui-and-cleanup.md](task-03-ui-and-cleanup.md): UI reads images from the API; remove OriginalApp
- [ ] [task-04-docs-and-verification.md](task-04-docs-and-verification.md): Documentation and verification

---

## Shared Context

### Overview
Today `ClaimDetail.tsx` builds image paths from the claim id (`original_car${id}.jpg`, `car${id}-processed.jpg`).
Those files reach `dist/` only because the legacy `OriginalApp.tsx` imports them, so every claim with
id > 6 shows broken images. Issue 5's email intake will store customer photos, which needs a backend store.

### Implementation Outcome
`ClaimImage` now stores image bytes in PostgreSQL and serves metadata and image content through claim-nested REST endpoints; the empty-table startup seeder resolves claims by claim number and skips missing claims. The UI now uses API-provided URLs, and the legacy page and unused assets are removed; API, seeder, Jest, Playwright, clean-compilation, package-build, and diagram-render checks have passed.

### Project Context
- `src/main/webui/src/app/components/ClaimDetail/ClaimDetail.tsx` (lines ~24–25 build the paths) and `ImageCarousel.tsx` (prefixes `/images/`).
- `src/main/webui/webpack.common.js`: the `file-loader` rule for `src/app/assets/images` emits only imported files.
- `src/main/webui/src/app/components/OriginalApp/OriginalApp.tsx` and its `/OriginalApp` route in `routes.tsx`.
- Issue 2 has already made `claimNumber` a natural id. Use it to attach seed images.

### Key Decisions
- Storage: a `ClaimImage` table with PostgreSQL `bytea` (no object storage, no filesystem).
- Images are linked to `Claim.id`. Seed images are matched to claims by claim number.
- Kinds: `ORIGINAL` (customer photos) and `PROCESSED` (annotated). New claims never get processed images.
- Remove `OriginalApp.tsx` and every asset nothing else references.
- Error responses use RFC 9457 Problem Details.

### Caveats & Problems
- `%prod`/`%openshift` recreate the schema on every start, so the seeder must be idempotent and must not fail startup.
- Tests must clean up the claims and images they create (`ClaimsListPageTests` expects 6 claims).
- Agent builds don't have real API keys. Only compilation is a trustworthy signal.