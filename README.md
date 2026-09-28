# NeoForge Meta API

A JSON-based HTTP API for querying metadata about NeoForge and Minecraft versions.

## API Endpoints

See [meta.yaml](./meta.yaml).

## Database

In development the service uses an embedded SQLite database. For production (Kubernetes)
it runs against PostgreSQL — see [Deployment](#deployment-kubernetes) below.

## Indexing

The service indexes Minecraft and NeoForge versions, but is aimed at indexing all of our released software components.

Each Maven component that the service should index must be listed in the application configuration file. It allows
specifying groupId/artifactId, which repository the artifacts are published to, and special rules which artifacts
are expected for each version range of the component, to accommodate the set of published artifacts changing over time.

## Webhooks

The API can invoke webhooks when versions change.

See `net.neoforged.meta.config.trigger.TriggersProperties` for details.

## Authentication

The API allows for three types of authentication. Two are intended for external consumers, while the third is used
only internally by NeoForge team members.

### API-Key Authentication

External consumers of the API can authenticate using an API key header. To get an API key, contact us
on [Discord](https://discord.neoforged.net/).

The name of the HTTP header is `X-API-Key`.

### GitHub Actions

If your code is running inside a GitHub Actions Workflow, you can also use the built-in OpenID Connect Token to
authenticate
with the Meta API. Our API requires the token audience `neoforged-meta-api`.

Example Workflow to access the API:

```yaml
name: API Test

on:
  workflow_dispatch:

permissions:
  id-token: write

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Get OIDC Token
        id: oidc
        uses: actions/github-script@v7
        with:
          script: core.setOutput('token', await core.getIDToken('neoforge-meta-api'))
      - name: Query API
        run: |
          curl --fail -H "Authorization: Bearer ${META_API_TOKEN}" https://meta-api.prod.k8s.neoforged.net/v1/neoforge-versions/ -v -o response
          cat response | jq
        env:
          META_API_TOKEN: "${{ steps.oidc.outputs.token }}"
```

### User Authentication

If a browser is used to access the API, it will redirect to our IDP (Dex), which authenticates the user using
GitHub and establishes a Session-Cookie. This is used to inspect the data stored in the API and debug issues
by the NeoForge team and is not available to external users.

## Deployment (Kubernetes)

The service is designed to run as multiple replicas against a shared PostgreSQL database.
Scheduled discovery jobs are coordinated between replicas via distributed database locks,
so exactly one replica runs each job per scheduled slot. See `.agent/plans/ha-setup.md`
for the full design.

The canonical deployment artifact is the Helm chart in [`helm/meta-api`](./helm/meta-api).
It supports both clusters that use **Ingress** (legacy) and clusters that use the
**Gateway API** (newer); enable exactly one of them:

```bash
# Legacy cluster — Ingress
helm install meta-api ./helm/meta-api \
  -n meta --create-namespace \
  --set image.tag=1.0.0 \
  --set ingress.enabled=true \
  --set ingress.host=meta-api.prod.k8s.neoforged.net

# Newer cluster — Gateway API (requires the gateway.networking.k8s.io CRDs)
helm install meta-api ./helm/meta-api \
  -n meta --create-namespace \
  --set image.tag=1.0.0 \
  --set gateway.enabled=true \
  --set gateway.parentRef.name=<gateway-name> \
  --set 'gateway.hostnames[0]=meta-api.prod.k8s.neoforged.net'
```
The chart expects an existing Secret `meta-api-db` with keys `url`, `username` and
`password` (override the name via `database.existingSecret`). To run API-only replicas,
set `scheduledTasks.mavenVersionDiscoveryEnabled=false` and
`scheduledTasks.minecraftVersionDiscoveryEnabled=false` on them, keeping at least one
replica with both enabled.

### Environment variables

| Variable | Required | Description |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | yes (K8s) | Must be `postgres` for HA deployments. |
| `DB_URL` | yes (K8s) | PostgreSQL JDBC URL, e.g. `jdbc:postgresql://db:5432/metaapi`. |
| `DB_USERNAME` / `DB_PASSWORD` | yes (K8s) | Database credentials. |
| `DB_POOL_SIZE` | no | HikariCP max pool size per replica (default 15). |
| `INSTANCE_ID` | no | Stable identifier for this instance in job locks and logs. Generated from the pod hostname if unset. |

### Health & probes

- `/actuator/health/liveness` — process liveness (K8s liveness + startup probe).
- `/actuator/health/readiness` — traffic readiness; includes database connectivity, connection pool stats,
  Flyway migration status, and which jobs this instance is currently running.
- `/actuator/prometheus` — metrics for the monitoring stack.

Both health paths are publicly reachable (K8S probes do not authenticate); all other actuator endpoints
require the admin role.
