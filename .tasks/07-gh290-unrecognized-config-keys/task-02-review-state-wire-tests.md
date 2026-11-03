# Task 02: Close the `ClaimReviewState` Wire-Shape Coverage Gap

**Type:** Test Addition

## Goal

`GET /api/db/claims/{id}/review` has raw-JSON tests pinning its exact key set and its `MissingItem` labels, green
against the **current** (reflective) serializer — so that task 03's Jackson change is measured against a real
before/after guard rather than against whatever the new code happens to produce.

## What to Do

Add to `src/test/java/org/parasol/intake/review/rest/ClaimReviewResourceTests.java`, following the convention already
in `src/test/java/org/parasol/claim/rest/ClaimResourceTests.java:93` (`jsonFieldNames`) and `:139`
(`categoryIsSerializedAsLabel`), and `ClaimCorrespondenceResourceTests.java:111`:

1. **`jsonFieldNames`** — assert the exact raw key set of the review response. Read it as raw JSON
   (`.jsonPath().getMap("$").keySet()` or equivalent), **not** through `.as(ClaimReviewState.class)`, and include the
   same explanatory comment the other classes carry: a typed round-trip can't see the wire format, because the same
   `@JsonNaming` serializes and deserializes.
2. **The `MissingItem` labels** — assert that `requested_items` and the full item list carry `MissingItem`'s
   `@JsonValue` labels (`MissingItem.java:29`, e.g. `"incident description"`), not the enum constant names. Mirror
   `ClaimResourceTests.categoryIsSerializedAsLabel`'s `@ParameterizedTest` / `@EnumSource` shape if it fits cleanly;
   a single assertion over a claim with a known requested set is acceptable if parameterizing would need a Flow run
   per constant.

Decide while writing whether `SentimentAssessment` (`POST /api/db/claims/{id}/sentiment`, a single `sentiment` field,
so snake_case is a no-op there) needs the same treatment, and record the decision in `PLAN.md` → Outcomes. One
assertion on its single key is cheap; skipping it with a stated reason is also fine.

Use the fixture style already in the class. Note that an **open** review (`ClaimReviewState.open == true`) only exists
for a claim with a real waiting `ClaimIntakeFlow` run — see how the existing tests in this class and
`ClaimReviewPanelPageTests` get one via `IntakeScenario`. If a directly-persisted fixture claim gives a closed-review
response whose key set is identical, prefer it; if the key set differs (an omitted `non-empty` field), pin the open
case, since that is the one the UI reads.

## Files/Areas

- `src/test/java/org/parasol/intake/review/rest/ClaimReviewResourceTests.java` (new tests only)

## Key Points

- **This is the one real coverage gap.** Every other REST DTO already has a raw-key test:
  `ClaimResourceTests.jsonFieldNames`, `ClaimImageResourceTests.metadataJsonFieldNames`,
  `ClaimCorrespondenceResourceTests.jsonFieldNames`, plus the negative
  `ClaimRetrievalHasNoIntakeBookkeepingTests`. `ClaimReviewState` and `SentimentAssessment` have none.
- **The frontend reads these keys literally** — `src/main/webui/src/app/components/ClaimDetail/ReviewPanel.tsx:12-13`
  and `PendingInformation.tsx:20`. A silent rename would break the review panel, not just a test.
- **Add nothing to the request side.** `ReviewDecisionRequest` already has raw-body coverage
  (`ClaimReviewResourceTests:496` `rawBody`, `:353` `body("{}")`) and its 400/409/422 behavior is well covered. This
  task is about the **response**.
- **No `@TestProfile`.** `ClaimReviewResourceTests` is in the bare `%test` group; adding a profile would be another
  full application boot (`CLAUDE.md` → *Testing*). Nothing here needs one.
- AssertJ, chained, one `assertThat` where possible (`CODE_STANDARDS.md` and the global style rules).
- **These tests must be green before task 03 starts.** If one of them fails on the current tree, that is a finding to
  raise, not something to adjust the assertion around.

## Done When

- [ ] `GET /api/db/claims/{id}/review` has a `jsonFieldNames` test asserting an exact raw key set.
- [ ] `MissingItem` values on the wire are asserted to be `@JsonValue` labels.
- [ ] A decision on `SentimentAssessment` is made and recorded in `PLAN.md`.
- [ ] `./mvnw test -Dtest=ClaimReviewResourceTests` is green on the **unmodified** `application.yml` Jackson key.
- [ ] `./mvnw -B clean verify -Pollama` is green.
