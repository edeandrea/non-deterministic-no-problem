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
    node-version: 24.15.0
    npm-version: 11.12.1
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
| `test`, `test:watch`, `test:coverage` | Jest test suite |
| `lint` / `eslint` | ESLint over `./src/` |
| `format` | Prettier over `./src/**/*.{tsx,ts}` |
| `type-check` | `tsc --noEmit` |
| `ci-checks` | `type-check` + `lint` + `test:coverage` |
| `build:bundle-profile`, `bundle-profile:analyze` | bundle size analysis |
| `clean` | `rimraf dist` |
| `dr:surge` | `node dr-surge.js` (deploy-preview helper) |

## Talking to the backend

[`src/app/config.tsx`](./src/app/config.tsx) resolves the backend base URL once:

```ts
backend_api_url: process.env.BACKEND_API_URL || window.location.protocol + '//' + window.location.host + '/api'
```

`BACKEND_API_URL` is injected at build time via `dotenv-webpack` (`systemvars: true`), so an exported
shell variable or a `.env` file both work. The root [`pom.xml`](../../../pom.xml) sets
`BACKEND_API_URL=http://localhost:8081/api` for the surefire and failsafe executions.

* **REST** — `ClaimsList` and `ClaimDetail` call `GET {backend_api_url}/db/claims` and
  `GET {backend_api_url}/db/claims/{id}` with axios.
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
import loader from '@app/assets/images/loader.gif';
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