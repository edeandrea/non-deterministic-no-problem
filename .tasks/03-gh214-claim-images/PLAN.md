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
- Follow the global rules in `CODE_STANDARDS.md` (older plans call it `AGENTS.md`).
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

### History: Copilot attempt and takeover (PR #225)
A first attempt by GitHub Copilot (PR #225, `4d9c299`…`5377583`) ticked every task without the approval gates, while
the maintainer's review requested changes and CI was red. The review found mixed layers (queries and Problem Details
built in the REST resource, seeder logic on the entity), an unrestricted stored content type, absolute image URLs, and
plan text that contradicted the code. Every box was unticked, and the work was redone on the same branch (squash-only
merges keep `main` clean). Copilot's asset moves, `OriginalApp` removal and most of the UI/docs were kept.

### Task Outcomes
- **Task 01:** `ClaimImage` (Active Record: `listForClaim`, `findForClaim`, `hasImage`, `store`) with an allow-listed
  `ClaimImageContentType` enum, lazily loaded `bytea` data (`Length.LONG32`), an `on delete cascade` foreign key and an
  index on `claim_id`. `ClaimImageResource` has no queries; `ClaimNotFoundException` / `ClaimImageNotFoundException` are
  mapped once to Problem Details by `ClaimExceptionMappings` (`@ServerExceptionMapper`). `ClaimMapper` (MapStruct
  1.6.3, `JAKARTA_CDI`) builds the DTO with a root-relative URL. Tests: `ClaimImageTests` (9, incl. the `bytea` column
  check), `ClaimImageResourceTests` (8), `ClaimImageContentTypeTests` (21).
- **Task 02:** `ClaimImageSeeder` matches the sample claims by id, re-inserts any missing image on every start, checks
  the claim once (log and skip if missing) and reads a file only when inserting it. A missing resource file fails
  startup (`SeedImageNotFoundException`), since that's a packaging bug. `ClaimImageSeederTests` (6) never touch the
  shared seed data: inserts and deletes happen on a claim each test creates.
- **Task 03:** `ClaimDetail` aborts both requests when the claim id changes (stale-response race), and the image logic
  lives in `utils/claimImages.ts` (`imagesToDisplay`, `imageSource` resolves the relative URL against
  `backend_api_url`'s origin; Jest-tested). Pre-existing bug fixed: the "Original claim content" accordion had its
  `isHidden` inverted (shown when "collapsed"); it now starts expanded with the attached images. `file-loader` removed
  from `package.json`. `ClaimImagesPageTests` (3) uses auto-waiting Playwright assertions; the production bundle
  ships only `images/favicon.svg`.
- **Task 04 (agent part done; maintainer steps open):** docs updated (`CLAUDE.md`, `README.md`,
  `src/main/webui/README.md`, a #216 plan note to resolve attachment types through `ClaimImageContentType`, and
  superseded notes in the #213 plan). `clean test-compile` passes for `-Pollama` and the default profile. A full
  `-Pollama` verify ran 157 tests with 2 errors, both the known environment baselines
  (`NotificationServiceTests.emailSendsWhenUserExists`, `LangfuseSessionScoringServiceTests:182`); every claim, image
  and UI suite passed. A fresh review found no BLOCKER; its one MAJOR (stale "by claim number" in task-04) and the
  relevant MINOR/NITs were fixed. The maintainer's real-key verify passed. Open: the maintainer's manual check (task
  stays unticked until then).

### Project Context
- `src/main/webui/src/app/components/ClaimDetail/ClaimDetail.tsx` (lines ~24–25 build the paths) and `ImageCarousel.tsx` (prefixes `/images/`).
- `src/main/webui/webpack.common.js`: the `file-loader` rule for `src/app/assets/images` emits only imported files.
- `src/main/webui/src/app/components/OriginalApp/OriginalApp.tsx` and its `/OriginalApp` route in `routes.tsx`.
- Issue 2 has already made `claimNumber` a natural id, but the numbers are generated by a sequence, so they can't be
  known (or hard-coded) up front.

### Key Decisions
- Storage: a `ClaimImage` table with PostgreSQL `bytea` (no object storage, no filesystem).
- Images are linked to `Claim.id`. **Seed images are matched to the seeded claims by their explicit ids (1–6) from
  `import.sql`** (maintainer decision on PR #225: claim numbers are generated, so they can't be listed in the seeder).
- **Seeding re-inserts any missing configured image on every start** (maintainer decision), not only when the table is
  empty. A missing claim is logged and skipped.
- Layering: `ClaimImage` (Panache Active Record, like `Claim`) owns persistence through domain-only methods; the REST
  resource holds no queries and builds no error responses; domain not-found exceptions are mapped once to Problem
  Details (a Quarkus `@ServerExceptionMapper` is allowed, maintainer decision).
- Entity → DTO mapping uses MapStruct with the Jakarta CDI component model (maintainer decision; latest stable).
- **`ClaimResource` is decoupled too** (maintainer decision during PR #225 review, reversing the original "unchanged"
  decision): it returns a `ClaimDetails` record through the same `ClaimMapper`, so no entity is serialized and #216's
  internal `Claim` columns can't leak into the API. The JSON stays identical; a missing claim becomes a Problem Details
  `404` (was an empty `204`). JsonViews were considered and rejected: the bytes endpoint isn't JSON, the image `url`
  isn't an entity field, and an entity would expose every new column by default.
- Kinds: `ORIGINAL` (customer photos) and `PROCESSED` (annotated). New claims never get processed images.
- Remove `OriginalApp.tsx` and every asset nothing else references.
- Error responses use RFC 9457 Problem Details.

### Caveats & Problems
- `%prod`/`%openshift` recreate the schema on every start, so the seeder must be idempotent and must not fail startup.
- Tests must clean up the claims and images they create (`ClaimsListPageTests` expects 6 claims).
- Agent builds don't have real API keys. Only compilation is a trustworthy signal.