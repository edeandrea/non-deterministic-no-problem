# Parasol Insurance frontend

This directory contains the React, TypeScript, and PatternFly frontend for the
Non-Deterministic? No Problem! demo. Quarkus Quinoa builds and serves it as part
of the main application.

## Integrated development

From the repository root, run:

```bash
./mvnw quarkus:dev
```

Quinoa installs the frontend dependencies and makes the application available
at <http://localhost:8080/>.

## Standalone development

Start the Quarkus backend, then run:

```bash
npm ci
BACKEND_API_URL=http://localhost:8080/api npm run start:dev
```

The Webpack development server listens on <http://localhost:8006/> by default.
`BACKEND_API_URL` supplies the REST base URL; the frontend derives the
WebSocket chat URL from it.

## Scripts

| Command | Purpose |
| --- | --- |
| `npm run start:dev` | Start the Webpack development server |
| `npm run build` | Type-check and create the production bundle in `dist/` |
| `npm test` | Run the Jest test suite |
| `npm run test:watch` | Run Jest in watch mode |
| `npm run test:coverage` | Run Jest with coverage |
| `npm run type-check` | Run the TypeScript compiler without emitting files |
| `npm run lint` | Run ESLint |
| `npm run format` | Format TypeScript sources with Prettier |
| `npm run ci-checks` | Run type checking, linting, and coverage tests |
| `npm run bundle-profile:analyze` | Build and inspect the Webpack bundle profile |

Use `HOST` and `PORT` to override the standalone server's default bind address
and port.
