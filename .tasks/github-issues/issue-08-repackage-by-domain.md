TITLE: Repackage org.parasol by domain, with layer sub-packages
## Summary

`org.parasol` is split by layer today (`ai`, `ai.guardrail`, `model.claim`, `resources`). The claim-intake work in #216 adds its code by domain (`org.parasol.intake` with `agent`, `mailbox`, `reply` and `review` sub-packages). This issue moves the existing code to the same layout: **domain first, then layer sub-packages**. It lands before #213, so the next issues don't add more files to the old layout.

## Why now

- #213 and #214 add `ClaimCategory`, `ClaimNumberGenerator`, `ClaimImage`, `ClaimImageKind`, `ClaimImageSeeder` and `ClaimImageResource`, so the move gets bigger with every issue that lands first.
- Once #216 ships, its database-backed agentic scope store saves fully qualified class names: the scope key is the root agent interface's FQCN, and the scope JSON tags each value with its type. Moving a class used in a scope after that breaks resuming reviews that are already stored.

## New layout

| Package | Contents | Was |
|---|---|---|
| `org.parasol.claim.model` | `Claim` | `model.claim` |
| `org.parasol.claim.rest` | `ClaimResource` | `resources` |
| `org.parasol.chat.ai` | `ClaimService` | `ai` |
| `org.parasol.chat.model` | `ClaimBotQuery`, `ClaimBotQueryResponse` | `model.claim` |
| `org.parasol.notification.service` | `NotificationService` | `ai` |
| `org.parasol.notification.ai` | `GenerateEmailService` | `ai` |
| `org.parasol.notification.model` | `Email`, `ClaimInfo` | `ai` |
| `org.parasol.notification.guardrail` | the email guardrails, `PolitenessService`, `StringUtils` | `ai.guardrail` |

Dependencies point one way: `chat` → `notification` → `claim`. Tests move with their classes; `org.parasol.ui` (Playwright) stays.

## Unchanged

- **Class names.** Langfuse dataset names and drift detection use the simple AI service class name, so a package move changes nothing there (a rename would).
- **Schema.** `Claim` keeps `@Table(name = "claims")`; no schema or seed-data change.
- **`ai.scoring`**, and behaviour in general. Visibility tightening is left for later so the diff stays a pure move.
- The stale `org.parasol.ai.*` names in `grafana-dashboard-ai.json` sit in the dead AI row that #218 removes.

## Tasks

- [x] Move the classes and tests; fix the packages and imports
- [x] Update `CLAUDE.md` and the file paths in the #213–#216 task plans
- [ ] Verify: compile and `-Pollama` tests (agent); default-profile `verify` with real keys (maintainer)

Part of #217
