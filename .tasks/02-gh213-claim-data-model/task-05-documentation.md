# Task 05: Update Documentation for the Data Model

**Type:** Code Modification

## Goal

Every document that describes claims, the seed data or the schema reflects the new model.

## What to Do

- Search the repository docs for `claim_time`, `time`, `category`, `claimNumber`, `CLM`, `import.sql`, `Claim` entity descriptions:
  - `README.md`, `CLAUDE.md`, `langfuse-evaluation.md`, `docs/*.puml`, `src/main/webui/README.md`
- Update `CLAUDE.md`:
  - **Architecture:** describe the `Claim` entity's natural-id claim number, the sequence (increment 1009), the category enum and the typed incident date/time.
  - **Gotchas:** add how the sequence is created and why `import.sql` must keep it in step (if applicable),
    and that tests must not assert absolute claim numbers.
- If any PlantUML diagram shows claim fields, update it and re-render with `./docs/render-diagrams.sh`.
- Leave `AGENTS.md` and `conversation-export.md` untouched.

## Files/Areas

- `CLAUDE.md`, `README.md`, `docs/`, `src/main/webui/README.md`

## Key Points

- Verify every sentence against the code you just changed. Don't describe intended behaviour that
  isn't implemented.

## Done When

- [ ] No document still refers to `claim_time` or a free-text category.
- [ ] `CLAUDE.md` documents the claim-number generation and its test caveat.