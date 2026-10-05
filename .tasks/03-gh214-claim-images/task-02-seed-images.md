# Task 02: Seed the Existing Claim Images

**Type:** Code Modification

## Goal

The six seeded claims have their existing original and processed images in the `ClaimImage` table in
every environment.

## What to Do

- Move the images used by seeded claims, `original_car{1..6}.jpg` and `car{1..6}-processed.jpg`, from
  `src/main/webui/src/app/assets/images/` to a backend resource folder (e.g.
  `src/main/resources/seed/claim-images/`).
- Add a startup seeder (`@Observes StartupEvent`, transactional):
  - when the `ClaimImage` table is empty, insert each image for its seeded claim
  - look claims up by **claim number** (the natural id from issue 2), not by id
  - store `original_*` as `ORIGINAL` and `*-processed` as `PROCESSED`
- The seeder must be idempotent (a second start doesn't add duplicates) and must log and skip a missing claim instead of failing startup.
- **Tests:**
  - after startup, each seeded claim has exactly one `ORIGINAL` and one `PROCESSED` image
  - re-running the seeder adds nothing
  - a missing claim number is skipped without an exception

## Files/Areas

- `src/main/resources/seed/claim-images/` (moved images)
- `src/main/java/org/parasol/claim/seed/ClaimImageSeeder.java` (new; any sensible sub-package of `org.parasol.claim`)
- `src/test/java/org/parasol/claim/...` (new tests)

## Key Points

- **Unused assets to classify:** `original_car0.jpg` / `car0-processed.jpg` (there's no claim 0),
  `car1.jpg`, `car2.jpg`, `car3.jpg`, `new_car1-3.png`, `sample.png`, and `bgimages/car1.jpg`.
  Find every reference before task 03 deletes them.
- `%prod` / `%openshift` drop and recreate the schema on each start, so the seeder runs on every
  cluster start. That's expected, and it's why idempotency matters.
- Keep the binaries small. Don't re-encode images.

## Done When

- [x] The images live under the backend resource folder, and a startup with an empty table seeds 12 images.
- [x] The idempotency and missing-claim tests pass.