# WorkOS → Keycloak Migrator

> :rocket: **Try it for free** in the new Phase Two [Keycloak managed service](https://phasetwo.io/?utm_source=github&utm_medium=readme&utm_campaign=workos-keycloak-migrator). Go to [Phase Two](https://phasetwo.io/) for more information.

Three-component project that migrates an identity system from WorkOS into Keycloak (with Phase Two's
organizations & webhooks extensions installed). 

| Module | Artifact | Role |
| --- | --- | --- |
| `common/` | `common-0.1.0-SNAPSHOT.jar` | WorkOS client, sync logic, webhook verification, state tracking |
| `migrator/` | `workos-keycloak-migrator.jar` (fat) | Standalone bulk runner |
| `extensions/webhook-listener/` | `workos-webhook-listener.jar` | Keycloak `RealmResourceProvider` at `/realms/{realm}/workos-webhook/{publicId}` |
| `extensions/slow-migration/` | `workos-slow-migration.jar` | `RealmResourceProvider` at `/realms/{realm}/workos-legacy/{username}` for `keycloak-user-migration` |
| `integration-tests/` | (test-only) | Failsafe IT module that drives the migrator + both extensions against a live Keycloak container |

See `IMPLEMENTATION.md` for the full design contract and `SPEC.md` for the original brief.

## How to use

1. **Build the artifacts.**

   ```
   mvn -DskipTests package
   ```

   Produces `migrator/target/workos-keycloak-migrator.jar` (fat jar) plus
   `extensions/{webhook-listener,slow-migration}/target/*.jar` for Keycloak.

2. **Stand up Keycloak with the Phase Two extensions installed.** Either reuse an existing
   Phase Two-enabled Keycloak or bring up the bundled local stack:

   ```
   docker compose up -d
   ```

   The compose file builds `docker/Dockerfile` — Keycloak 26.7.3 with the Phase Two providers
   layered in — and mounts both extension jars into `/opt/keycloak/providers/`. The
   `keycloak-rest-provider` jar that the slow-migration flow needs ships inside that image, so
   there is nothing to download by hand.

3. **Create the target realm + a service-account client.**

   ```
   ./scripts/bootstrap-realm.sh
   ```

   Creates the `migrate-target` realm (with `sslRequired=NONE` and
   `unmanagedAttributePolicy=ENABLED`), provisions a `migrator-cli` service-account client
   with `realm-admin`, and prints the credentials.

4. **Run the bulk migrator.**

   ```
   java -jar migrator/target/workos-keycloak-migrator.jar \
     --workos-api-key=$WORKOS_API_KEY \
     --keycloak-url=http://localhost:8080 \
     --keycloak-realm=migrate-target \
     --keycloak-client-id=migrator-cli \
     --keycloak-client-secret=$KC_CLIENT_SECRET \
     --source-label=sandbox
   ```

   The runner is idempotent — re-running picks up where the last run left off (cursors are
   persisted as realm attributes) and reports `SKIPPED reason=unchanged` for entities that
   haven't drifted.

5. **(Optional) Enable live syncing via the extensions.** Both are opt-in per realm so set
   `KC_SPI_REALM_RESTAPI_EXTENSION_WORKOS_WEBHOOK_REALMS=migrate-target` and
   `KC_SPI_REALM_RESTAPI_EXTENSION_WORKOS_LEGACY_REALMS=migrate-target` (or `*` for all),
   then restart Keycloak. The webhook listener auto-registers a WorkOS webhook endpoint
   pointing at `${KC_HOSTNAME_URL}/realms/{realm}/workos-webhook/{publicId}`, and the
   slow-migration extension installs the `keycloak-rest-provider` federation component
   so passwords get verified against WorkOS on first login.

6. **Re-run the bulk migrator** whenever you want a full reconciliation (e.g. after WorkOS
   admins make changes that the webhook listener didn't catch). The same command from step
   4 — no flags needed for the happy path.

## Build

```
mvn -DskipTests package
```

### Code formatting

The project uses [Google Java Format](https://github.com/google/google-java-format) via
`com.spotify.fmt:fmt-maven-plugin:2.29`. Run it manually with:

```
mvn fmt:format     # rewrite files in place
mvn fmt:check      # fail if anything is mis-formatted (good for CI)
```

A versioned pre-commit hook in `.githooks/pre-commit` runs `mvn fmt:format` whenever any
Java file is staged, then re-stages the rewritten files. Enable it once per clone with:

```
git config core.hooksPath .githooks
```

(Use `git commit --no-verify` to bypass for hot-fix scenarios.)

Outputs:
- `migrator/target/workos-keycloak-migrator.jar`
- `extensions/webhook-listener/target/workos-webhook-listener.jar`
- `extensions/slow-migration/target/workos-slow-migration.jar`

## Run the bulk migrator

```
java -jar migrator/target/workos-keycloak-migrator.jar \
  --workos-api-key=$WORKOS_API_KEY \
  --keycloak-url=http://localhost:8080 \
  --keycloak-realm=migrate-target \
  --keycloak-client-id=migrator-cli \
  --keycloak-client-secret=$KC_CLIENT_SECRET \
  --source-label=sandbox
```

Re-running the same command is safe; entities migrated previously will be detected by their
`workos.id` attribute and either updated or left untouched if their content hash hasn't changed.

Useful flags: `--entities=users,organizations`, `--dry-run`, `--restart`, `--limit=10`,
`--slow-migration-active` (skip `UPDATE_PASSWORD` since the slow-migration extension will handle the
password trade-off on first login).

## Local validation harness

The repo ships a docker-compose stack that brings up Postgres + the Keycloak 26.7.3 image from
`docker/Dockerfile` with our two extensions mounted as providers.

1. Build the project: `mvn -DskipTests package`
2. `WORKOS_API_KEY=sk_test_... WORKOS_CLIENT_ID=client_... WORKOS_CLIENT_SECRET=... docker compose up -d --build`
3. `./scripts/bootstrap-realm.sh` — prints the migrator credentials.
4. Run the bulk migrator (see above).
5. Trigger a WorkOS event (e.g. update a user) — observe the webhook hit and the realm state.
6. Reset a user’s password from the Keycloak login screen — observe the slow-migration extension
   verify it against WorkOS via `keycloak-user-migration`.

## Attribute & realm-state conventions

All written keys are prefixed `workos.`. The canonical list is in
`io.phasetwo.migration.common.AttributeKeys`. Notable entries:

- `workos.id` (user / org / role): WorkOS source-of-truth id; primary idempotency key.
- `workos.source`: short label for the WorkOS environment (`sandbox`, `demo`, or a fingerprint).
- `workos.migration.client_fingerprint` (realm): SHA-256 prefix of the WorkOS API key — prevents
  accidentally swapping a Sandbox key for a Demo key on the same realm.
- `workos.migration.cursor.<entity>` (realm): resume cursor written page-by-page.
- `workos.migration.counts.<entity>.<action>` (realm): per-action counts persisted at run end.
- `workos.migration.webhook.*` (realm): the listener stores its public id, the WorkOS endpoint id,
  and the HMAC secret here.
- `workos.migration.slow.*` (realm): credentials for the `keycloak-user-migration` legacy service.

## Notes / known partials

- WorkOS Connections (`/connections`) do not expose IdP secrets or SSO URLs. The migrator therefore
  creates **stub** identity providers tagged with `workos.incomplete=true` so administrators can
  finish wiring. Where SAML signing certs are available we set them.
- WorkOS Directories likewise become SCIM **stubs** in the corresponding Phase Two org with a
  placeholder shared secret; the org is tagged `workos.directory.incomplete=true`.
- Phase Two organization roles have no permission model. WorkOS permission slugs are encoded into
  the role description as `[permissions:p1,p2]` plus a `[wos:role_…]` suffix that carries the
  original id.
- Phase Two organization-domain `verified` is set via the verify() API; if PT rejects (e.g. no DNS
  record present in a lab env) the run reports `PARTIAL` and leaves the domain unverified.
- Directory groups (`dsync.group.*`) materialise as Keycloak groups under a per-org parent named
  `org-{pt_org_id}` (the Phase Two organization id, not the WorkOS id).
- **Slow-migration & bulk migration interaction**: when the slow-migration federation provider is
  installed, Keycloak's user-creation API consults it to prevent duplicates. A user that exists in
  WorkOS but not yet locally is "shadow known" via federation; the bulk migrator's `POST /users`
  will then return 409 and the run reports the entity as `SKIPPED reason=conflict_during_create`.
  This is intentional — those users will be materialised on first login by the legacy provider
  contract. Run the bulk migrator with the federation component disabled if you want fully
  eager local user creation.

## Extension configuration

Both Keycloak extensions read configuration via Keycloak's standard SPI mechanism:
`KC_SPI_REALM_RESTAPI_EXTENSION_WORKOS_WEBHOOK_<KEY>` for the webhook listener and
`KC_SPI_REALM_RESTAPI_EXTENSION_WORKOS_LEGACY_<KEY>` for the slow-migration extension. Both
honour the same `realms` opt-in:

| Variable | Default | Meaning |
| --- | --- | --- |
| `realms` | _(empty)_ | **No realms** are touched by `postInit`. Operators opt in. |
| `realms=*` | | Every realm is provisioned at startup (legacy/wildcard behaviour). |
| `realms=foo,bar` | | Only `foo` and `bar` are provisioned. |

If `realms` is unset/empty the extension still serves HTTP traffic at its REST path; the only
thing it skips is the auto-provisioning step that registers a WorkOS webhook endpoint (for the
listener) or installs the `keycloak-rest-provider` component (for the legacy extension). The
default was switched to opt-in so a one-shot `docker compose up` doesn't accidentally fan out
across every realm in a shared Keycloak — operators must name the realms they actually want
migrated.

The `docker-compose.yml` wires the variable through `${WORKOS_MIGRATION_REALMS:-migrate-target}`
so the bootstrap script's realm name is the default opt-in.

## SCIM directory users

WorkOS marks users created through a Directory Sync (SCIM) connection by attaching them to an
`OrganizationMembership` with `directory_managed=true`, and by listing them at
`/directory_users`. The migrator surfaces this in Keycloak in two ways:

- The realm role `scim-managed` is created on the first run (idempotent) and granted to any
  user who shows up as a directory user. Filter for that role in the Keycloak admin UI to see
  every user that originated from SCIM.
- The user gains the attributes `scim.directory_id`, `scim.directory_user_id`,
  `scim.idp_id`, and `scim.state` linking back to the WorkOS records.

The tagging happens through three paths so each entry point converges on the same shape:

1. The new bulk step `directory_users` (run by default; selectable via `--entities=…`) pages
   every WorkOS directory and tags its users.
2. The existing `memberships` step flips the role on whenever it sees
   `directory_managed=true`.
3. The webhook listener's `dsync.user.created` / `dsync.user.updated` events route to the same
   `DirectoryUserSync`, so live updates apply the tag without waiting for a bulk run.

## Testing

- `mvn test` — unit tests for the webhook verifier, rate limiter, JSON mappings, and the IdP
  provider-id mapper. No Docker required (~30 tests, < 5 s).
- `mvn -Pit verify` — runs the unit suite plus the integration-test module. Requires a
  reachable Docker host; on first run it pulls `quay.io/keycloak/keycloak:26.7.3` plus
  `quay.io/phasetwo/phasetwo-keycloak:26.6.6` and builds the combined image from
  `docker/Dockerfile` (cached thereafter).

### Integration tests

The IT module lives at `integration-tests/` and is gated behind the `-Pit` Maven profile so
`mvn test` stays fast and Docker-free. It contains three test classes:

#### `BulkMigratorIT`

Boots the Keycloak container, points an in-JVM WireMock at the migrator's
`--workos-base-url`, then drives the CLI in two ordered methods that share one container.

- `happyPath_imports_workos_state` — runs the migrator against the canned fixture set
  (3 organisations, 3 users, 2 environment roles, 2 SAML/OIDC connections) and asserts:
  - The per-entity action counts (`CREATED` for users / orgs / roles, `PARTIAL` for the SAML
    stubs) as captured by a logback `ListAppender`.
  - The realm's admin REST state — a known user carries every `workos.*` attribute including
    `workos.id`, `workos.external_id`, `workos.metadata.timezone`, and `workos.sync_hash`.
  - The realm-scoped tracking attributes (`workos.migration.last_run_at`,
    `client_fingerprint`, `last_run_status=OK`).
- `idempotency_rerun_is_noop_for_users` — re-runs the migrator with everything already in
  place; asserts every user line in the captured log returns
  `SKIPPED reason=unchanged`, with zero further `CREATED` user lines.

#### `WebhookListenerIT`

Mounts the `workos-webhook-listener.jar` into `/opt/keycloak/providers/`, seeds the realm with
a known `webhook.public_id` and HMAC `secret`, then drives the resource over HTTP:

- `validSignature_returns_200` — signs a `user.updated` payload with the seeded secret; asserts
  200 and that the in-process `KeycloakSession` mutated the targeted user's last name.
- `badSignature_returns_401` — tampered signature is rejected without side effects.
- `missingSignature_returns_401` — missing header is rejected.
- `unknownPublicId_returns_404` — a mismatched `publicId` on a real realm returns 404
  (validates the per-realm UUID gate).
- `userDeleted_removes_user` — a signed `user.deleted` event removes the user from the realm.

#### `SlowMigrationIT`

Mounts `workos-slow-migration.jar`, exposes the WireMock host port to Testcontainers *before*
starting Keycloak (so `host.testcontainers.internal` is routable from inside the container),
then drives the `keycloak-user-migration` legacy-service contract:

- `federationComponent_can_be_installed_with_upstream_provider_id` — installs a
  `ComponentModel` with `providerId="User migration using a REST client"` and asserts it
  resolves; confirms our extension lines up with the upstream
  `keycloak-rest-provider:6.2.3` that the phasetwo image ships.
- `getKnownUser_returns_200_with_workos_shape` — `GET /workos-legacy/alice@…` with a valid
  bearer returns 200, the JSON body has the `username` / `email` / `emailVerified` /
  `attributes.workos.id` fields, and the `organizations[]` field is omitted (per spec).
- `getUnknownUser_returns_404` — WireMock returns an empty list; the resource maps that to 404.
- `missingBearer_returns_401` / `wrongBearer_returns_401` — bearer-token authentication is
  enforced.
- `postCorrectPassword_returns_200` / `postWrongPassword_returns_401` — WireMock stubs the
  WorkOS `/user_management/authenticate` endpoint with 200 / 401 and the resource passes the
  status through.

#### Running locally

```
mvn -Pit verify
```

Each IT class boots its own container (~12 s) and reuses it across the methods in that class
via `@TestInstance(PER_CLASS)` + a `static @Container` (or, for `SlowMigrationIT`, a manually
managed container so `Testcontainers.exposeHostPorts(...)` runs first). Total runtime is
≈ 35 s on a warm Docker host.
