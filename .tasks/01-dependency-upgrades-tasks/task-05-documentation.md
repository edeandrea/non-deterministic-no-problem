# Task 05: Update Documentation for the Version Bumps

**Type:** Code Modification

## Goal

Every document that states a version or depends on version-specific behaviour matches the upgraded build.

## What to Do

- Search all documentation for version strings and version-sensitive statements:
  - `README.md`, `CLAUDE.md`, `langfuse-evaluation.md`
  - `docs/*.puml`, `src/main/webui/README.md`
  - Javadoc in `src/main/java`
  - Search terms: `1.13`, `3.40`, `quarkus-langchain4j`, `langchain4j 1.`, `Java 2`, `node`, image tags
- Update anything the bumps made stale.
- If a release note changed behaviour this repo documents (e.g. guardrail retry semantics, chat-scope
  routing, span naming), update the affected `CLAUDE.md` Gotchas entry.
- Leave `AGENTS.md` untouched. It holds the user's global rules and is deliberately untracked.
- Leave `conversation-export.md` untouched. It's a historical transcript.

## Files/Areas

- `README.md`, `CLAUDE.md`, `langfuse-evaluation.md`, `docs/`, `src/main/webui/README.md`

## Key Points

- Verify every statement against the actual source (pom, yml, library sources) before writing it. Don't infer.
- If a `.puml` changes, re-render with `./docs/render-diagrams.sh` and compare the PNG dimensions
  with the previous render. A wildly different size means something broke.

## Done When

- [ ] A repository-wide search for the old version numbers returns only intentional historical mentions.
- [ ] Every doc statement touched by this issue has been checked against source, and `PLAN.md` lists the files changed.