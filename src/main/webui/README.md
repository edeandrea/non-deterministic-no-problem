# Parasol Insurance Web UI

The React + TypeScript + [PatternFly](https://www.patternfly.org/) frontend for the Parasol Insurance
claims demo. It is served by the Quarkus backend through the
[Quinoa](https://docs.quarkiverse.io/quarkus-quinoa/dev/) extension — it is **not** a standalone app.

See the repository root [`README.md`](../../../README.md) and [`CLAUDE.md`](../../../CLAUDE.md) for
overall project context.

## How it is built

You normally never run npm by hand. Quinoa runs the npm install/build for you as part of the Maven
build from the repository root:

```bash
./mvnw quarkus:dev   # live coding, frontend included
./mvnw package       # production build, frontend bundled into the app
```

Quinoa configuration lives in [`src/main/resources/application.yml`](../resources/application.yml)
under `quarkus.quinoa`:

```yaml
quinoa:
  build-dir: dist
  enable-spa-routing: true
  package-manager-install:
    ~: true
    node-version: 24.21.0
    npm-version: 11.21.0
```

`package-manager-install` means Node and npm are downloaded and pinned by the build — you do not need
them installed locally.

## npm scripts

These are the scripts actually defined in [`package.json`](./package.json):

| Script | Purpose |
| --- | --- |
| `start:dev` | webpack dev server (`webpack.dev.js`) |
| `build` | production build into `dist` (runs `prebuild`: `type-check` + `clean`) |
| `start` | serve an already-built `dist` with `sirv` on port 8080 |
| `test`, `test:watch`, `test:coverage` | Jest test suite (also run by the Maven build; see below) |
| `lint` / `eslint` | ESLint over `./src/` |
| `format` | Prettier over `./src/**/*.{tsx,ts}` |
| `type-check` | `tsc --noEmit` |
| `ci-checks` | `type-check` + `lint` + `test:coverage` |
| `build:bundle-profile`, `bundle-profile:analyze` | bundle size analysis |
| `clean` | `rimraf dist` |
| `dr:surge` | `node dr-surge.js` (deploy-preview helper) |

## Tests

Jest tests live next to the code (`*.test.ts` / `*.test.tsx`).
- **What they cover:** `src/app/app.test.tsx` renders the whole app against a snapshot in
  `src/app/__snapshots__/`. `src/app/utils/formatIncident.test.ts` covers the incident date/time formatting,
  `claimImages.test.ts` which images the detail page shows and their URLs, and `httpErrors.test.ts` the 404 check.
- **Run by Maven:** the Playwright `@QuarkusTest`s use `UiTestProfile`, which carries Quinoa's `EnableAndRunTests` overrides, so Quinoa runs
  `npm test` during their build, and a failing Jest test fails the Maven test run.
- **Config:** [`jest.config.js`](./jest.config.js) maps CSS and asset imports to the stubs in
  [`__mocks__/`](./__mocks__). It also maps deep `@patternfly/react-icons/dist/esm/...` imports to the CommonJS
  `dist/js/...` build, because Jest doesn't transform `node_modules`.
- **Updating the snapshot:** after an intentional layout change, run `npx jest -u` and commit the updated snapshot.
- **No backend under Jest:** `app.test.tsx` renders the app under jsdom with no backend, so it stubs `axios.get`
  with a request that never settles. Nothing goes over the network, and no state update lands after the render
  (outside `act`). The snapshot always shows an empty claims table: it checks the page structure (shell, filters,
  table headers), not claim rendering. Claim rendering is covered by the Playwright tests in
  `src/test/java/org/parasol/ui/`.
- **A test that logs fails:** [`jest.setup.ts`](./jest.setup.ts) (`setupFilesAfterEnv`) records every
  `console.error` / `console.warn` call, still printing it, and fails the test in `afterEach` if there was one. React's
  `act(...)` warnings and jsdom's errors (a failed XHR) arrive there, so a green run prints no console output. A log that
  arrives asynchronously, after its test has finished, is charged to whichever test is running then, and is missed if
  none is. Fix the cause (wrap the update in `act`, stub the request) rather than muting the console.

## Talking to the backend

[`src/app/config.tsx`](./src/app/config.tsx) resolves the backend base URL once:

```ts
backend_api_url: process.env.BACKEND_API_URL || window.location.protocol + '//' + window.location.host + '/api'
```

`BACKEND_API_URL` is injected at build time via `dotenv-webpack` (`systemvars: true`), so an exported
shell variable or a `.env` file both work. The root [`pom.xml`](../../../pom.xml) sets
`BACKEND_API_URL=http://localhost:8081/api` for the surefire and failsafe executions.

* **REST** — `ClaimsList` and `ClaimDetail` call `GET {backend_api_url}/db/claims` and
  `GET {backend_api_url}/db/claims/{id}` with axios. Claim JSON is snake_case (the backend's `ClaimDetails` DTO). An
  unknown id is a Problem Details `404`; `ClaimDetail` then shows a "Claim not found" page with a link back to the
  list (`isNotFound` in [`httpErrors.ts`](./src/app/utils/httpErrors.ts)) instead of an empty claim:
  - `category` is the display label ("Single vehicle", "Multiple vehicle", "Theft", "Other"), which is what the list's
    category filter matches on.
  - `incident_date` (`YYYY-MM-DD`) and the optional `incident_time` (`HH:mm:ss`, left out when null) are formatted
    for display by [`formatIncident.ts`](./src/app/utils/formatIncident.ts). It parses them by hand rather than with
    `new Date(...)`, which treats a date-only string as UTC midnight and shows the previous day west of UTC.
  - `ClaimDetail` also gets `GET {backend_api_url}/db/claims/{id}/images`. Each entry's `url` is root-relative
    (`/api/db/claims/{id}/images/{imageId}`); `imageSource` in [`claimImages.ts`](./src/app/utils/claimImages.ts)
    resolves it against the origin of `backend_api_url`, so the bytes come from the same server as the rest of the API.
    The Documents tab shows the `ORIGINAL` images; the right-hand panel shows the `PROCESSED` ones, otherwise the
    originals, otherwise "No images attached" (`imagesToDisplay`).
  - Both requests are aborted when the claim id changes, so a slow response for the previous claim can't overwrite the
    current one.
* **Chat** — [`Chat.tsx`](./src/app/components/Chat/Chat.tsx) is a WebSocket, not REST. It derives the
  socket URL from the same config value by swapping the scheme and stripping the `/api` suffix:

  ```ts
  const wsUrl = config.backend_api_url.replace(/^http/, 'ws').replace(/\/api$/, '') + '/_chat/routes';
  ```

  It connects with the `ChatScopesClient` (loaded in [`src/index.html`](./src/index.html) from
  `/_chat/javascript/chatscopes.js`) to the route named `chat`, and sends:

  ```json
  { "query": { "claimId": "...", "query": "...", "claim": "...", "inceptionDate": "..." } }
  ```

## Path aliases

Configured in [`tsconfig.json`](./tsconfig.json) and wired into webpack via `TsconfigPathsPlugin`
in [`webpack.common.js`](./webpack.common.js):

* `@app/*` → `src/app/*`
* `@assets/*` → PatternFly's `@patternfly/react-core/dist/styles/assets/*`

```js
import imgSrc from '@assets/images/g_sizing.png';
```

SVGs under a `bgimages` directory are inlined as data URIs (see `BG_IMAGES_DIRNAME` in
`webpack.common.js`), which is what makes them usable as CSS `background` images.

## Adding custom CSS

When importing CSS from a third-party package for the first time you may hit
`Module parse failed: Unexpected token...`. Register the stylesheet directory in
[`stylePaths.js`](./stylePaths.js) — it is consumed by `webpack.dev.js` and `webpack.prod.js` and is
explicit for performance, so webpack does not crawl all of `node_modules`.

Other config: [TypeScript](./tsconfig.json) · [webpack](./webpack.common.js) · [Jest](./jest.config.js) · [EditorConfig](./.editorconfig)
