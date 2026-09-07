# WorkOS → Keycloak Migrator — Implementation Plan

This document is the implementation contract for the three components described in `SPEC.md`. It is meant to be exhaustive enough that the build can proceed without further re-design. The final section ("Open Questions") lists points where I want a confirmation before writing code, so they all land in a single review round.

---

## 1. Project layout

Multi-module Maven build, JDK 17, parent `pom.xml` in the repo root.

```
workos-keycloak-migrator/
├── pom.xml                                   parent / dependencyManagement
├── common/                                   shared library (jar)
│   └── src/main/java/io/phasetwo/wkm/common/...
│   └── src/test/java/io/phasetwo/wkm/common/...
├── migrator/                                 CLI app — bulk runner (fat jar)
│   └── src/main/java/io/phasetwo/wkm/migrator/...
│   └── src/test/java/io/phasetwo/wkm/migrator/...
├── extensions/                               parent for Keycloak SPI jars
│   ├── pom.xml
│   ├── webhook-listener/                     workos-webhook RealmResourceProvider
│   │   └── src/main/java/io/phasetwo/wkm/webhook/...
│   │   └── src/main/resources/META-INF/services/
│   │         org.keycloak.services.resource.RealmResourceProviderFactory
│   │   └── src/test/java/io/phasetwo/wkm/webhook/...
│   └── slow-migration/                       legacy-service RealmResourceProvider
│       └── src/main/java/io/phasetwo/wkm/legacy/...
│       └── src/main/resources/META-INF/services/...
│       └── src/test/java/io/phasetwo/wkm/legacy/...
├── docker-compose.yml                        local Keycloak + Postgres + extensions
├── openapi/                                  (existing, untouched)
├── SPEC.md                                   (existing)
└── IMPLEMENTATION.md                         (this file)
```

Two separate extension jars (one per `### ...extension` heading in SPEC) — both drop into `/opt/keycloak/providers/`. Sharing `common` keeps the sync logic identical to the migrator.

### Parent pom highlights

- `java.version` = 21, `maven.compiler.release` = 21
- `keycloak.version` = 26.7.3 (current upstream Keycloak; ahead of Phase Two’s newest published
  image tag, see the docker-compose notes below)
- `phasetwo-client.version` = latest of `io.phasetwo:phasetwo-admin-client`
- `workos.version` = `5.x` (`com.workos:workos`, Kotlin SDK with documented Java interop)
- `dependencyManagement` pins: Keycloak BOM, Jackson, slf4j 2.x, junit-bom 5.10, testcontainers BOM, wiremock 3, picocli 4, bucket4j-core 8, kotlin-stdlib 2.1.
- `<modules>`: common, migrator, extensions (which itself lists webhook-listener and slow-migration).
- Distribution profiles: `release` (signs & shades), `it` (enables Testcontainers integration tests behind `failsafe`).

### Per-module packaging

| Module | Packaging | Notes |
| --- | --- | --- |
| `common` | jar | depends on Keycloak `server-spi-private` as `provided`, WorkOS SDK, Phase Two client, HTTP/Jackson. |
| `migrator` | jar (fat via `maven-shade-plugin`) | main class `io.phasetwo.migration.migrator.Main`; service files for picocli & SLF4J relocated; `Multi-Release` manifest entry. |
| `extensions/webhook-listener` | jar | uses `provided` Keycloak deps; shades only non-Keycloak runtime deps (Jackson is already on Keycloak’s classpath, so we rely on it; bucket4j shaded). |
| `extensions/slow-migration` | jar | same model. |

---

## 2. Shared `common` library

All entity mapping logic lives here so it is reused by the migrator and both extensions.

### 2.1 Packages

```
io.phasetwo.migration.common
├── workos/                 WorkOS client facade
│   ├── WorkOSClient         (interface)
│   ├── WorkOSSdkClient      (impl wrapping com.workos.WorkOS Kotlin SDK)
│   ├── WorkOSHttpClient     (impl using OkHttp + Jackson for endpoints the SDK does not cover, e.g. /user_management/users/{id}/identities, /sso/profile, /webhook_endpoints)
│   ├── WorkOSPaginator      (cursor-based pager for `list_metadata.before/after`)
│   └── model/...            (POJOs mirroring openapi/workos-openapi.yaml: User, Organization, OrgDomain, Connection, Directory, Role, Identity, WebhookEndpoint, WebhookEvent)
├── keycloak/               Keycloak + Phase Two facade
│   ├── KeycloakAdmin        (wraps org.keycloak.admin.client.Keycloak)
│   ├── PhaseTwoAdmin        (wraps io.phasetwo.client.PhaseTwo)
│   └── lookup/              UserLookup, OrgLookup, RoleLookup, IdpLookup, ScimLookup
├── sync/                   per-entity synchronisers, idempotent
│   ├── EntitySync<S,T>      generic interface { Result sync(S source); }
│   ├── UserSync
│   ├── RoleSync             (environment + organization variants)
│   ├── OrganizationSync
│   ├── OrganizationDomainSync
│   ├── OrganizationMembershipSync
│   ├── IdentityProviderSync
│   ├── DirectorySync        (SCIM provider stub creator)
│   └── SyncResult           { entityType, action ∈ {CREATED,UPDATED,SKIPPED,FAILED,PARTIAL}, workosId, keycloakId, errors[], notes[] }
├── state/                  migration bookkeeping
│   ├── MigrationState       reads/writes realm attrs prefixed `workos.migration.*`
│   ├── EntityState          reads/writes user/org attrs prefixed `workos.*`
│   ├── ResumeCursor         per-entity-type cursor
│   └── Counters             per-entity-type {created,updated,skipped,failed}
├── webhook/
│   ├── WebhookVerifier      verifies `WorkOS-Signature: t=...,v1=...` per docs
│   ├── WebhookEventRouter   dispatches by `event` to sync/* (same code path as bulk)
│   └── WebhookEvent         envelope { id, event, data, created_at }
├── ratelimit/
│   └── RateLimiter          Bucket4j-backed limiter, 6,000 requests / 60 s globally + sub-buckets for `/directory_users` (4/s) and `/organizations/{id}` DELETE (50/60s). Adapts to 429 with exponential backoff (250 ms → 5 s, jittered, 5 attempts max).
├── logging/
│   └── ProgressReporter     fan-in to slf4j + (optionally) JSONL on stdout for migrator
└── util/                   ID helpers, attribute keys, JSON utils, time
```

### 2.2 Attribute conventions

Single source of truth in `common.AttributeKeys`. All written keys are prefixed `workos.` to be greppable; values are stringly typed.

| Scope | Key | Purpose |
| --- | --- | --- |
| User attr | `workos.id` | WorkOS user id (`user_…`). Primary idempotency lookup. |
| User attr | `workos.external_id` | mirror of WorkOS `external_id` for round-trip lookups. |
| User attr | `workos.metadata.<k>` | flattened WorkOS user metadata (≤ 50 keys, ≤ 600 chars). |
| User attr | `workos.profile_picture_url` | URL. |
| User attr | `workos.locale`, `workos.last_sign_in_at` | mirror. |
| User attr | `workos.migrated_at` | ISO timestamp of first migration. |
| User attr | `workos.last_sync_at` | ISO timestamp of latest reconciliation. |
| User attr | `workos.source` | `sandbox` / `demo` / SHA-256 prefix of API key — distinguishes runs against multiple envs. |
| Org attr | `workos.id`, `workos.external_id`, `workos.metadata.<k>`, `workos.stripe_customer_id`, `workos.migrated_at`, `workos.last_sync_at`, `workos.source` | same intent as user. |
| Org role attr | `workos.id`, `workos.slug`, `workos.permissions` (CSV) | informational. |
| Realm role attr | `workos.id`, `workos.slug`, `workos.permissions` | informational. |
| IdP config | `workos.connection_id`, `workos.connection_type`, `workos.incomplete` | mark stub IdPs. |
| Realm attr | `workos.migration.client_fingerprint` | SHA-256(api_key) — guards against accidentally swapping environments. |
| Realm attr | `workos.migration.last_run_at`, `workos.migration.last_run_status` | tracker. |
| Realm attr | `workos.migration.cursor.<entity>` | resume cursor. |
| Realm attr | `workos.migration.counts.<entity>.<created\|updated\|skipped\|failed>` | counters. |
| Realm attr | `workos.migration.webhook.public_id` | UUID embedded in the listener URL. |
| Realm attr | `workos.migration.webhook.endpoint_id` | WorkOS `we_…` id, so we don’t create duplicates. |
| Realm attr | `workos.migration.webhook.secret` | shared secret from WorkOS. |
| Realm attr | `workos.migration.slow.token` | bearer token used by keycloak-user-migration. |
| Realm attr | `workos.migration.slow.client_id`, `workos.migration.slow.client_secret` | WorkOS AuthKit creds for the `password` grant. |

### 2.3 Idempotency strategy

For every synchroniser:
1. **Lookup by stable WorkOS id** stored in attribute (`workos.id`). If hit, update path.
2. **Fallback lookup**: users → by email; orgs → by name + first verified domain; roles → by name; idps → by alias `workos-<connection_id>`; SCIM → by org id (only one SCIM config per org).
3. **Conflict policy**: when a fallback lookup matches but the entity already carries a *different* `workos.id`, log warning, mark `SKIPPED`, do not overwrite (administrator decides).
4. **Update vs. no-op**: compute a deterministic JSON sketch (sorted keys, normalised) of the WorkOS-derived fields, hash with SHA-256, store as `workos.sync_hash`. On re-runs, skip if hash matches → fast & cheap reruns.

### 2.4 Entity mappings (source-of-truth tables)

#### Users — WorkOS `UserlandUser` → Keycloak `UserRepresentation`

| Target (KC) | Source (WorkOS) | Notes |
| --- | --- | --- |
| `username` | `email` | WorkOS users have no username; email is unique. |
| `email` | `email` | |
| `emailVerified` | `email_verified` | |
| `firstName` | `first_name` | |
| `lastName` | `last_name` | |
| `enabled` | `true` | WorkOS has no disabled flag at the user object level. |
| `createdTimestamp` | `created_at` (epoch ms) | |
| `attributes.workos.id` | `id` | idempotency key. |
| `attributes.workos.external_id` | `external_id` | |
| `attributes.workos.locale` | `locale` | |
| `attributes.workos.profile_picture_url` | `profile_picture_url` | |
| `attributes.workos.last_sign_in_at` | `last_sign_in_at` | |
| `attributes.workos.metadata.<k>` | `metadata[k]` | one attr per pair. |
| `requiredActions` | `["UPDATE_PASSWORD"]` if migrator is *not* paired with the slow-migration extension; `[]` otherwise (because slow-migration will trade the WorkOS password on first login). | |
| `federatedIdentities[]` | one element per entry returned by `GET /user_management/users/{id}/identities`. `identityProvider` = the Keycloak IdP alias mapped from the OAuth `provider` (e.g. `GoogleOAuth` → `google`); `userId` = `idp_id`; `userName` = `email`. | If no Keycloak IdP exists for that provider, create a *linkOnly* stub IdP `oauth-<provider-lowercased>` and link to it (admin must finish configuring). |

Organization memberships are written *after* both the user and the org exist. Use `OrganizationMembershipSync` to: ensure the user is a member of the corresponding PT org via `PUT /{realm}/orgs/{orgId}/members/{userId}`, then assign org roles for that user matching the WorkOS `role_assignments` for the membership, storing `om.id` as a membership-attribute on the PT side under `workos.org_membership_id`.

#### Roles — WorkOS `Role`

- `type=EnvironmentRole`: create Keycloak **realm role** with name = `slug`. Attributes carry the description, permissions list, and `workos.id`.
- `type=OrganizationRole`: per organization. Lookup org via `workos.id` attr, then create a Phase Two **org role** through `POST /{realm}/orgs/{orgId}/roles` with `name=slug`, `description=description`. Permissions are not first-class in PT org roles, so we keep them as attribute `workos.permissions` on the role *via a post-fixup PUT* (PT supports description; permissions only flow as user-facing string in description as `[permissions:foo,bar]`). The org role’s WorkOS id is stored as a description suffix `[wos:role_…]` (since PT roles do not have attribute storage in the OpenAPI surface).

#### Organizations — WorkOS `Organization` → Phase Two `OrganizationRepresentation`

| Target | Source | Notes |
| --- | --- | --- |
| `name` | `name` | If a PT org already has the same name but a different `workos.id`, the run **skips** with a warning. |
| `displayName` | `name` | |
| `domains[]` | every `domains[*].domain` regardless of verification state | PT stores them as plain strings on the org. |
| `attributes.workos.id` | `id` | |
| `attributes.workos.external_id` | `external_id` | |
| `attributes.workos.stripe_customer_id` | `stripe_customer_id` | |
| `attributes.workos.metadata.<k>` | `metadata[k]` | |

After create, iterate WorkOS `domains[]` and for each one whose `state ∈ {verified, legacy_verified}`, call PT `GET /{realm}/orgs/{orgId}/domains/{domain}` → if `verified=false`, PATCH the underlying domain by re-issuing the org PUT with `verified=true` (the OrganizationDomainRepresentation includes the field). This is **subject to Q2 in Open Questions** — falls back to leaving them unverified and emitting a `PARTIAL` result if PT rejects the write.

#### Identity Providers — WorkOS `Connection` → Phase Two `IdentityProviderRepresentation`

Mapping table from `connection_type` to Keycloak `providerId`:

| WorkOS `connection_type` | KC `providerId` | Notes |
| --- | --- | --- |
| `*SAML` (e.g. `OktaSAML`, `AzureSAML`, `GenericSAML`, `ADFSSAML`, `JumpCloudSAML`, `KeycloakSAML`, `PingOneSAML`, etc.) | `saml` | If `options.signing_cert` is non-null, set config `signingCertificate=<base64>` and `validateSignature=true`. SSO/SLO URLs are not available from `/connections` — set `singleSignOnServiceUrl=""` and add config `workos.incomplete=true`. |
| `GenericOIDC`, `Auth0SAML` (sic, OIDC variants), `EntraIdOIDC`, `LoginGovOidc`, `OktaOIDC`, `GoogleOIDC`, `CleverOIDC`, `AdpOidc` | `oidc` | `clientId`, `clientSecret`, `tokenUrl`, etc. all unknown → `workos.incomplete=true`. |
| `GoogleOAuth` | `google` | Stub: empty `clientId`/`clientSecret`. |
| `MicrosoftOAuth` | `microsoft` | |
| `GitHubOAuth` | `github` | |
| `GitLabOAuth` | `gitlab` | |
| `LinkedInOAuth` | `linkedin-openid-connect` | |
| `AppleOAuth` | `apple` | |
| `SlackOAuth`, `DiscordOAuth`, `BitbucketOAuth`, `XeroOAuth`, `IntuitOAuth`, `SalesforceOAuth`, `VercelOAuth`, `VercelMarketplaceOAuth` | `oidc` stub with `workos.connection_type` retained as config. | Keycloak ships no built-in providers for these. |
| `Auth0Migration`, `MagicLink`, `TestIdp`, `Pending`, anything else | skipped, logged with `SKIPPED` reason `unmapped_connection_type`. | |

Common config fields written for every created IdP:
- `alias` = `workos-<connection.id>` (deterministic, idempotent).
- `displayName` = `connection.name`.
- `enabled` = `connection.state == active`.
- `firstBrokerLoginFlowAlias` = realm default unless overridden.
- `config.workos.connection_id` = `connection.id`.
- `config.workos.connection_type` = original enum.
- `config.workos.incomplete` = `"true"` whenever required IdP config is missing.

Domains: for each WorkOS `Connection.domains[*]`, ensure the corresponding PT org has that domain (re-use `OrganizationDomainSync`).

#### Directory connections — WorkOS `Directory` → Phase Two SCIM provider

- Per org (`directory.organization_id` → PT org via `workos.id` attr), call `POST /{realm}/orgs/{orgId}/scim` with:
  ```json
  {
    "enabled": <state == linked>,
    "email_as_username": true,
    "link_idp": false,
    "auth": { "type": "EXTERNAL_SECRET", "shared_secret": "<placeholder PHC string>" }
  }
  ```
- Tag the org with attributes `workos.directory.id`, `workos.directory.type`, `workos.directory.state`, `workos.directory.incomplete=true` so the admin knows to wire real auth.
- If a SCIM config already exists (`GET` returns 200), `PUT` is unavailable in the documented surface — we **leave the existing config alone** and log `SKIPPED reason=scim_exists`.

### 2.5 WorkOS client facade

- `WorkOSClient` interface exposes typed methods:
  - `listUsers(cursor)`, `getUser(id)`, `listUserIdentities(userId)`
  - `listOrganizations(cursor)`, `getOrganization(id)`
  - `listConnections(cursor)`, `getConnection(id)`
  - `listDirectories(cursor)`, `getDirectory(id)`
  - `listRoles(cursor)`, `listOrganizationRoles(orgId, cursor)`
  - `listOrganizationMemberships(filters, cursor)`
  - `listOrganizationDomains(orgId)`
  - `authenticatePassword(email, password, clientId, clientSecret)`
  - `createWebhookEndpoint(...)`, `listWebhookEndpoints()`, `updateWebhookEndpoint(...)`, `deleteWebhookEndpoint(id)`
- Two implementations live behind the interface:
  - **SDK-backed paths** (`com.workos.WorkOS`) for everything the SDK already exposes (users, organizations, organization-memberships, roles, connections, directories, password authentication, webhooks). The Kotlin SDK is JDK-17 + Kotlin 2.1; we bundle `kotlin-stdlib` transitively. Public API of our facade hides Kotlin types.
  - **HTTP paths** (`OkHttp` + Jackson) for endpoints the SDK lacks at the time of writing (`/user_management/users/{id}/identities`, `/sso/profile`, individual `/connections/{id}` if needed). Both implementations share a single `RateLimiter` instance + 429 retry handler.
- Both implementations log every request line at DEBUG with method+path+latency+status and include a request-id we generate (`X-Request-Id`).

### 2.6 Phase Two / Keycloak facade

- `KeycloakAdmin` constructed once per CLI run / once per extension lifecycle:
  ```java
  Keycloak kc = KeycloakBuilder.builder()
      .serverUrl(serverUrl).realm(realm)
      .clientId(clientId).clientSecret(clientSecret)
      .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
      .build();
  ```
- `PhaseTwoAdmin` wraps the Phase Two client builder against the same token (re-uses `kc.tokenManager()`).
- Lookups always normalise: usernames lower-cased, attribute searches use `q=workos.id:user_xxx` (Keycloak v25+ supports attribute search).

### 2.7 Rate limiter

- Bucket4j with hierarchical buckets:
  - global: 6,000 / 60 s.
  - per-endpoint overrides keyed by path template: `/directory_users` → 4 / 1 s; `/organizations/{id}` DELETE → 50 / 60 s; AuthKit reads/writes/auth as documented (used by the slow-migration extension password endpoint).
- On `HTTP 429`, retry with exponential backoff respecting `Retry-After` if present (the docs don’t guarantee it, but we honor it when seen), capped at 5 attempts. Surface a `RateLimitExceeded` to the caller after.
- Single shared instance per process; thread-safe so it works inside Keycloak request threads.

### 2.8 Resume / restart semantics

- The runner is idempotent; resume is a *fast-skip* path:
  - For each entity type, read `workos.migration.cursor.<entity>` and start from there.
  - Per page, after success, write the page-end cursor back to the realm attribute *before* moving on.
  - Counts are incremented in realm attributes after each page completes.
- `--resume` is the default; `--restart` forces all cursors to be cleared first.
- A SIGTERM handler flushes the in-memory cursor immediately.

---

## 3. Bulk migration runner (`migrator/`)

### 3.1 CLI surface

Picocli-driven. `Main` implements `Runnable`. Flags:

| Flag | Required | Description |
| --- | --- | --- |
| `--workos-api-key` (or `WORKOS_API_KEY` env) | yes | WorkOS `sk_...`. |
| `--workos-base-url` | no, default `https://api.workos.com` | |
| `--keycloak-url` | yes | base URL incl. `/auth` if present. |
| `--keycloak-realm` | yes | target realm name. |
| `--keycloak-client-id` | yes | client_credentials client w/ `realm-admin`. |
| `--keycloak-client-secret` | yes | |
| `--entities` | no, default `all` | comma list: `users,organizations,roles,memberships,idps,directories`. |
| `--source-label` | no | overrides `workos.source` value (e.g. `sandbox`). |
| `--dry-run` | no | log intended writes, perform none. |
| `--restart` | no | clear all `workos.migration.cursor.*` first. |
| `--limit` | no | cap entities per type — helpful in test runs. |
| `--log-format` | no | `pretty` (default) or `json`. |
| `--continue-on-error` | no, default `true` | record failures, keep going. |
| `--config-file` | no | YAML override for all of the above. |

### 3.2 Execution order

1. **Bootstrap** — validate connections to WorkOS (GET `/organizations?limit=1`) and Keycloak (`GET /realms/{realm}`). Refuse to run if `workos.migration.client_fingerprint` is set on the realm and does not match the SHA-256 prefix of the supplied API key (prevents cross-env contamination); `--force-rebind` overrides.
2. **Roles** — environment roles first (so memberships can reference them).
3. **Organizations** — including domain reconciliation.
4. **Organization roles** — per org, after org exists.
5. **Identity providers** — per org, after org exists.
6. **Directories (SCIM)** — per org.
7. **Users** — after orgs/roles/idps so federated identities and memberships can hook on.
8. **Organization memberships** — uses cursor-based listing of `/user_management/organization_memberships`.

Each step is a `Step` object with `name`, `run()` and per-step rate-limiter wiring; `Step.run()` is wrapped in try/catch that increments the failure counter and continues unless `--continue-on-error=false`.

### 3.3 Logging & reporting

- SLF4J + logback; default pattern shows step, entity, action, source-id, target-id, latency.
- A final report is printed *and* persisted as a realm attribute `workos.migration.last_run_summary` (JSON, ≤ 2 KB).
- Non-zero exit code if any failure beyond `--continue-on-error` threshold (`--max-failures` default `0` when `--continue-on-error=false`, else unlimited).

### 3.4 Tests

- Unit (`mvn test`):
  - mapping tests for every entity (input fixtures in `src/test/resources/workos/*.json`).
  - rate limiter (synthetic ticks).
  - cursor persistence across simulated restarts.
- Integration (`mvn -Pit verify`):
  - Testcontainers spins up `quay.io/phasetwo/phasetwo-keycloak:latest` + Postgres.
  - WireMock stands in for `api.workos.com` with canned WorkOS payloads.
  - Run `Main.main(...)`, then assert Keycloak state via the admin client.
  - Re-run the same `Main.main(...)` and assert idempotency (every action returns `SKIPPED unchanged`).

---

## 4. Webhook listener extension (`extensions/webhook-listener/`)

### 4.1 Provider registration

- `WorkOSWebhookProviderFactory implements RealmResourceProviderFactory`
  - `getId()` → `"workos-webhook"` so requests land at `/realms/{realm}/workos-webhook/...`.
  - `create(KeycloakSession)` returns `WorkOSWebhookProvider`.
  - `postInit(KeycloakSessionFactory)` runs the provisioning task once per JVM:
    1. For each realm matching the configured filter (`KC_SPI_REALM_RESOURCE_WORKOS_WEBHOOK_REALMS=*` by default or comma list), open a session:
       - Skip realm if no `workos.migration.slow.client_id` *and* no `WORKOS_API_KEY` provider variable is set for that realm (it isn’t a WorkOS-migrated realm).
       - Read `workos.migration.webhook.public_id`; if absent, generate `UUID.randomUUID().toString()` and persist.
       - Compute the public endpoint URL: `${WKM_WEBHOOK_BASE_URL or KC_HOSTNAME_URL}/realms/{realm}/workos-webhook/{public_id}`.
       - Call WorkOS `GET /webhook_endpoints` (paginated, filter by URL == ours): if found, reuse its id + secret; else `POST /webhook_endpoints` with the URL and our event list (see below). Persist `workos.migration.webhook.endpoint_id` and `workos.migration.webhook.secret`.
       - If the endpoint exists but has a stale event set, `PATCH` it.
    2. Failures are logged and retried with exponential backoff up to 1 hour; the provider still serves traffic regardless (idempotent re-registration on next startup).
  - `close()` is a no-op (we deliberately leave the WorkOS endpoint registered so traffic survives restarts).
- Resource class: `WorkOSWebhookResource`:
  - `POST /{publicId}` — single entry point.
    - Reject if `{publicId} != workos.migration.webhook.public_id`.
    - Read full body, lookup `WorkOS-Signature` header, run `WebhookVerifier.verify(body, header, secret, tolerance=5min)`. Return 401 on failure.
    - Parse `WebhookEvent` and dispatch via `WebhookEventRouter` to the matching `common.sync.*` synchroniser. Return 2xx on success.
    - On synchroniser failure, return 500 so WorkOS retries (per docs, 6 retries over 3 days).

### 4.2 Subscribed events

Exactly the entity-events that map back to the bulk runner:

```
user.created
user.updated
user.deleted
organization.created
organization.updated
organization.deleted
organization_membership.created
organization_membership.updated
organization_membership.deleted
organization_domain.created
organization_domain.updated
organization_domain.verified
organization_domain.deleted
role.created                  (EnvironmentRole)
role.updated
role.deleted
organization_role.created
organization_role.updated
organization_role.deleted
connection.activated
connection.deactivated
connection.deleted
connection.saml_certificate_renewed
dsync.activated
dsync.deleted
dsync.group.created           (optional — see Q below)
dsync.group.updated
dsync.group.deleted
dsync.user.created
dsync.user.updated
dsync.user.deleted
```

dsync.user.* feeds the **User** synchroniser (re-using the same id-attribute lookup); dsync.group.* will create no-op stubs but record the membership info so the admin can see what is in WorkOS.

### 4.3 Configuration (provider variables)

Read with `Config.scope("workosWebhook")` (Keycloak config DSL):

| Key | Default | |
| --- | --- | --- |
| `apiKey` | unset (required) | WorkOS API key. |
| `apiBaseUrl` | `https://api.workos.com` | |
| `webhookBaseUrl` | `${KC_HOSTNAME_URL}` | public URL prefix for the listener. |
| `eventTolerance` | `300` | seconds tolerated for signature timestamp drift. |
| `provisionWebhook` | `true` | set to false to disable auto-creation in WorkOS (manual mode). |
| `realms` | `*` | comma-separated list of realm names to provision against. |

Sample command-line set in docker-compose: `--spi-realm-restapi-extension-workos-webhook-api-key=...`.

### 4.4 Webhook signature verification

Following WorkOS docs:
1. Parse header `WorkOS-Signature` → tokens by comma; extract `t=` and `v1=`.
2. Reject if `|now - t/1000| > eventTolerance`.
3. Compute `HMAC-SHA256(secret, t + "." + rawBody)` (hex).
4. Constant-time compare to `v1`.
Tests cover: valid signature, expired timestamp, tampered body, wrong key, missing header.

### 4.5 Tests

Testcontainers KC + WireMock for WorkOS API responses. Cases:
- Provisioning creates a new webhook endpoint on first start.
- Provisioning re-uses an existing endpoint URL on restart.
- Valid signed event for `user.updated` mutates the KC user attribute.
- Invalid signature → 401, no mutation.
- Unsupported event type → 200 (ack) but no mutation.
- Synchroniser error → 500 so WorkOS retries.

---

## 5. Slow migration extension (`extensions/slow-migration/`)

Implements the legacy service contract expected by `keycloak-user-migration`.

### 5.1 Provider registration

- `WorkOSLegacyProviderFactory implements RealmResourceProviderFactory`
  - `getId()` → `"workos-legacy"` → requests land at `/realms/{realm}/workos-legacy/...`.
  - `postInit(KeycloakSessionFactory)`:
    1. For each in-scope realm, open a session and ensure a `ComponentModel` exists with:
       - `providerType = org.keycloak.storage.UserStorageProvider`
       - `providerId = "ext-remote-user-federation"` (the keycloak-user-migration extension's component id)
       - `name = "workos-legacy-migration"`  ← naming convention guarantees idempotency.
       - `config.URI` = `${KC_HOSTNAME_URL}/realms/{realmName}/workos-legacy`
       - `config.API_TOKEN` = randomly generated bearer (stored as realm attr `workos.migration.slow.token`).
       - `config.USE_USER_ID_FOR_CREDENTIAL_VERIFICATION` = `false` (we want username-based POSTs).
    2. Also ensure realm attrs are populated: `workos.migration.slow.client_id` and `workos.migration.slow.client_secret` (taken from provider config) for AuthKit `password` grant.

### 5.2 Resource

`WorkOSLegacyResource`:
- `@GET /{username}`: bearer-token auth; lookup WorkOS user via `GET /user_management/users?email={username}` (page 1, first result); if not found 404; else fetch identities with `GET /user_management/users/{id}/identities`. Return body:
  ```json
  {
    "id": null,
    "username": "<email>",
    "email": "<email>",
    "firstName": "...",
    "lastName": "...",
    "enabled": true,
    "emailVerified": true,
    "attributes": {
      "workos.id": ["user_..."],
      "workos.external_id": ["..."],
      "workos.locale": ["..."],
      "workos.profile_picture_url": ["..."],
      "workos.last_sign_in_at": ["..."]
    },
    "roles": [],
    "groups": [],
    "requiredActions": []
  }
  ```
  *Per SPEC*, the `organizations[]` field is omitted (it refers to native Keycloak organizations).
- `@POST /{username}`: bearer-token auth; body `{ "password": "<plaintext>" }`; call WorkOS `POST /user_management/authenticate` with `grant_type=password`. 200 on success; 401 on failure (any non-200 from WorkOS). Audit-log only that an attempt happened — never log the password.

### 5.3 Configuration (provider variables)

| Key | Default | |
| --- | --- | --- |
| `apiKey` | required | for the user lookup. |
| `clientId` | required | WorkOS AuthKit client id (for password grant). |
| `clientSecret` | required | |
| `apiBaseUrl` | `https://api.workos.com` | |
| `realms` | `*` | which realms to provision in. |
| `componentName` | `"workos-legacy-migration"` | for the auto-installed federation component. |

### 5.4 Tests

- Stub WorkOS with WireMock; full Testcontainers KC.
- GET happy path → 200 with body.
- GET unknown user → 404.
- POST correct password → 200.
- POST wrong password → 401.
- Missing/invalid bearer → 401.
- Auto-provisioning creates the federation component idempotently across restarts.

---

## 6. Docker-compose / local validation

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: keycloak
      POSTGRES_USER: keycloak
      POSTGRES_PASSWORD: keycloak
    volumes: [pgdata:/var/lib/postgresql/data]
  keycloak:
    # docker/Dockerfile: the Phase Two provider jars out of
    # quay.io/phasetwo/phasetwo-keycloak:26.6.6 layered onto quay.io/keycloak/keycloak:26.7.3.
    build: {context: ./docker}
    depends_on: [postgres]
    environment:
      KC_DB: postgres
      KC_DB_URL_HOST: postgres
      KC_DB_USERNAME: keycloak
      KC_DB_PASSWORD: keycloak
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: admin
      KC_HOSTNAME_URL: http://localhost:8080
      KC_HEALTH_ENABLED: "true"
      KC_HTTP_ENABLED: "true"
    ports: ["8080:8080"]
    volumes:
      - ./extensions/webhook-listener/target/webhook-listener-*.jar:/opt/keycloak/providers/webhook-listener.jar
      - ./extensions/slow-migration/target/slow-migration-*.jar:/opt/keycloak/providers/slow-migration.jar
    command: ["start-dev", "--features=organization"]
volumes:
  pgdata:
```

The keycloak-user-migration jar (`keycloak-rest-provider`) already ships inside the Phase Two provider set that `docker/Dockerfile` copies in, so there is nothing to download. A small Make-like script at `scripts/bootstrap-realm.sh` will:
1. Create realm `migrate-target`.
2. Create a confidential client `migrator-cli` with service-account + `realm-admin`.
3. Print the client-secret for use with the migrator.

Validation walkthrough (documented in `README` produced as part of code review):
1. `mvn clean install -DskipTests`
2. `docker compose up -d`
3. `scripts/bootstrap-realm.sh`
4. `java -jar migrator/target/migrator-*.jar --workos-api-key=$SANDBOX_KEY --keycloak-url=http://localhost:8080 --keycloak-realm=migrate-target --keycloak-client-id=migrator-cli --keycloak-client-secret=...`
5. Inspect Keycloak; rerun the migrator (expect all `SKIPPED unchanged`).
6. Trigger a WorkOS event (e.g. update a user) and observe the webhook hit + state update.
7. Reset the user password from Keycloak login UI and observe slow-migration verify.

---

## 7. Cross-cutting concerns

### 7.1 Security

- Never log API keys, passwords, or HMAC secrets. `RedactingMaskingConverter` wired into logback.
- Bearer token for slow-migration generated with `SecureRandom` (256-bit, Base64URL).
- Webhook signature verification uses `MessageDigest.isEqual` (constant time).
- HTTPS enforced for outbound WorkOS calls (and a system property gate for tests).

### 7.2 Observability

- All entity sync operations emit a structured log line with: entity_type, source_id, target_id, action, duration_ms, error_message (if any).
- Counters surfaced both in stdout and stored as realm attributes (for later UI display).
- Extensions emit Keycloak events with `type=WORKOS_WEBHOOK` / `WORKOS_LEGACY` for audit visibility.

### 7.3 Failure modes & idempotency tests

Each synchroniser has unit tests that cover:
- create path (no existing entity),
- update path (entity exists by `workos.id`),
- conflict path (entity exists by name but with mismatched `workos.id`),
- delete-on-webhook path,
- partial path (e.g. missing IdP config → still creates a stub, returns `PARTIAL`).

### 7.4 Build & CI

- GitHub Actions matrix (linux JDK 17): `mvn -B verify`. Integration tests run inside the same job (Docker is available).
- `mvn spotless:apply` for formatting; checkstyle (Google style).
- `mvn dependency-check:check` to flag CVEs at release time.

---

## 8. Estimated work breakdown (rough)

| Block | Days |
| --- | --- |
| Parent pom, modules wiring, dependency tuning | 0.5 |
| `common` — WorkOS facade, pagination, rate limiter | 1.5 |
| `common` — Keycloak/PT facade, attribute keys, MigrationState | 1.0 |
| `common` — synchronisers (users, orgs, roles, memberships, IdPs, SCIM) + idempotency tests | 2.5 |
| `common` — webhook verifier + event router | 0.5 |
| `migrator` — CLI, step orchestration, resume, reporting | 1.5 |
| `migrator` — integration tests (Testcontainers + WireMock) | 1.0 |
| `extensions/webhook-listener` — provider, resource, postInit, tests | 1.5 |
| `extensions/slow-migration` — provider, resource, postInit, tests | 1.0 |
| docker-compose, bootstrap script, README | 0.5 |
| Buffer for surprises in Phase Two API behaviour | 1.0 |
| **Total** | **~12.5 dev-days** |

---

## 9. Open questions (please confirm before implementation)

1. **PT organization domain `verified` field** — `OrganizationDomainRepresentation` exposes a boolean `verified`, but the documented mutation paths are `POST /verify` (which kicks off a DNS check) and the org PUT (which accepts the array). Plan is to PUT the org with `verified: true` for domains WorkOS reports as `verified` or `legacy_verified`. If that field is read-only in the underlying handler, we will fall back to leaving them unverified and surfacing a `PARTIAL` note. **OK?** Yes.
2. **WorkOS custom-role permissions** — Phase Two org roles model only id/name/description. We will encode WorkOS permissions into the role description (`[permissions:posts:read,posts:write] description text`) so they aren’t lost. The bulk runner will not create matching PT permission objects (PT roles don’t have them in the public API). **OK?** Yes.
3. **Username for migrated Keycloak users** — WorkOS users have no username, so we will use the email address. **OK?** Yes. We assume that Keycloak will have the "email as username" realm setting enabled.
4. **Multiple WorkOS environments** — the plan assumes one WorkOS environment per Keycloak realm and uses `workos.migration.client_fingerprint` to prevent mixing. Do you ever expect to feed Sandbox **and** Demo into the same realm? No.
5. **Service-account creation** — the migrator requires a Keycloak client with `client_credentials` + `realm-admin`. The bootstrap script will create one called `migrator-cli`. Confirm that name is acceptable; otherwise provide the convention to follow. Yes.
6. **Public URL for the webhook listener** — extension `postInit` needs to know how the realm is reachable from WorkOS. Plan: read `KC_HOSTNAME_URL` (set by Keycloak) and override with provider variable `webhookBaseUrl`. **OK?** If you want it discovered another way (e.g. realm attribute `frontendUrl`), say so. Fallback in this order: `KC_HOSTNAME_URL`, realm `frontentUrl`, variable `webhookBaseUrl`.
7. **keycloak-user-migration providerId** — I will assume the component’s `providerId` is `ext-remote-user-federation` (the canonical id from the upstream README). Please confirm the installed JAR uses that id (vs. a forked alias). The provider name from the user migration extension is here "User migration using a REST client"
8. **dsync group handling** — WorkOS Directory Groups don’t cleanly map to Phase Two organisations or KC groups (group-of-orgs vs. group-of-users). Plan: ignore `dsync.group.*` events for now and document this as a known gap. **OK?** Or: should we materialise them as KC groups under the org’s namespace? Create a KC group with the name `org-{org_id}` and add them as subgroups under that group.
9. **Stub IdPs for OAuth providers WorkOS supports but Keycloak doesn’t** (Slack, Discord, Bitbucket, etc.) — plan creates `providerId=oidc` stubs marked `workos.incomplete=true`. **OK?** yes.
10. **Initial password handling** — for users imported without the slow-migration extension live, we will set `requiredActions=[UPDATE_PASSWORD]` so the admin can force a reset. When the slow-migration extension is in place we omit that and rely on the federation flow. **OK?** Yes.
11. **Where to read WorkOS provider variables in the extensions** — plan uses Keycloak Config DSL (`Config.scope("workosWebhook")` etc.) which can be set via CLI args (`--spi-...`) or env vars (`KC_SPI_...`). **OK?** Yes.
12. **Maven Central availability** — `io.phasetwo:phasetwo-admin-client` needs to be in Maven Central at the latest version. Please confirm a pinned version we should target, otherwise I will lock to the highest version visible at build time. Use `0.1.15`.
13. **Test data on WorkOS Sandbox** — you mentioned creating entities as we see fit. I will create a small fixture set (≈ 5 orgs, 20 users, a couple of connections + directories, environment + custom roles). **OK?** Yes. Create any data you need. That account is for you.

Once these are signed off, I will proceed straight to implementation (per the brief).
