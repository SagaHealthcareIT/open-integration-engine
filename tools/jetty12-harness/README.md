# Jetty 12 migration — Stage 0 characterization harness

Companion tooling for the Jetty 9.4 → 12.0 migration plan
(`docs/Marketplace/Jetty12-Migration-Plan.md` in the project-mdds-llm workspace repo, section 4,
Stage 0). The migration's rule: every behavior below is measured on Jetty 9.4 **before** any jar
changes, and must measure identically after each migration stage.

## JUnit characterization tests (run with the normal suite)

Located under `server/test/`, named `*CharacterizationTest`, and run by `ant test-run` along with
everything else:

| Test | Pins |
|---|---|
| `connectors/http/HttpListenerCharacterizationTest` | HTTP Listener handler **fallthrough** semantics over real HTTP — static-resource hit/miss, query-param mismatch, non-GET, DIRECTORY missing-file, context precedence — plus basic auth through the `ConstraintSecurityHandler` wrapper. This is the canary for the bug that shipped broken for 5 weeks in BridgeLink's port. Contains **no Jetty imports**; it must never need edits during the migration. |
| `plugins/httpauth/digest/DigestAuthenticatorCharacterizationTest` | Digest `WWW-Authenticate` advertisement (ALL algorithms comma-joined in one quoted directive), `QuotedStringTokenizer` parsing of quoted values with commas/spaces, qop=auth / RFC 2069 / MD5-sess handshakes, nonce-count reuse, re-challenge-not-failure, and the stale-vs-opaque subtlety. |
| `server/JdbcSessionStoreCharacterizationTest` | `MirthWebServer.createSessionDataStoreFactory` wiring against **real Postgres**, in BOTH cache modes (`DefaultSessionCache` and the `server.api.sessioncache=none` `NullSessionCache` write-through mode): session row written to the custom-named table, readable by a fresh server instance on the same table (engine-restart scenario). Requires env vars — see below. Jetty imports/wiring here WILL move with the migration; assertions must not change. |
| `server/WebServerStartupRecoveryCharacterizationTest` | `MirthWebServer.startup()`'s recovery path — the tree's ONLY post-assembly handler-list mutation: start fails on a broken webapp → stop → remove it from the `HandlerList` → start again serving everything else. Maps to `ContextHandlerCollection.deployHandler/undeployHandler` in Stage 2. |
| `server/DatabaseAdaptorBlobTypeCharacterizationTest` | The SQL Server `image` blob-type `DatabaseAdaptor` override technique (protected `_blobType`, `getDBName()`, overridable `getBlobType()`). |

### Postgres-backed test

Workspace rule: SQL behavior is validated against a real database. The session store test skips
unless these are set (point them at a **scratch** database — the test creates/drops its own table):

```bash
export MIRTH_TEST_POSTGRES_URL="jdbc:postgresql://127.0.0.1:5432/jetty12_char"
export MIRTH_TEST_POSTGRES_USER=...
export MIRTH_TEST_POSTGRES_PASSWORD=...
```

In Stage 3 the same test must also be run in the split mode described in the plan: rows written by
a 9.4 build, read by the Jetty 12 build (the pre-upgrade-rows check).

### Running just the harness classes

```bash
cd server
ant -q test-compile
java -Xshare:off -cp "testlib/*:test_classes:classes:$(find lib -name '*.jar' | tr '\n' ':')" \
    org.junit.runner.JUnitCore \
    com.mirth.connect.connectors.http.HttpListenerCharacterizationTest \
    com.mirth.connect.plugins.httpauth.digest.DigestAuthenticatorCharacterizationTest \
    com.mirth.connect.server.JdbcSessionStoreCharacterizationTest \
    com.mirth.connect.server.DatabaseAdaptorBlobTypeCharacterizationTest
```

## Serving matrix (run against a live install)

`serving-matrix.sh` covers what the JUnit tests can't: the full `MirthWebServer` context assembly
(public_html, WebStart, client-lib, javadocs, launcher installers, API, Swagger, apiexamples) on a
real engine install. It needs a running server:

```bash
./serving-matrix.sh https://localhost:8443 > baseline-9.4.txt     # capture BEFORE migrating
# ... migrate ...
./serving-matrix.sh https://localhost:8443 > after.txt
diff baseline-9.4.txt after.txt
```

STATUS and CONTENT-TYPE must never drift. SIZE is authoritative for static resources only. 404s in
the baseline (unstaged installers, missing javadocs) are fine — the diff still catches change.
Append install-specific paths (deployed webapps, versioned API roots) as extra arguments so they
join the matrix. API paths are additionally probed with the `X-Requested-With` header (`[XRW]`
rows) — the bare-request CSRF 400 and the accepted 200 are both pinned.

The Stage 2 exit gate in the plan is: server boots and this matrix diffs clean against the 9.4
baseline captured on the same install layout.

## Captured baselines (`baselines/`)

| File | Source | Captured |
|---|---|---|
| `mirth-6.baseline-9.4.txt` | Container `d2c2d07e80ef` ("mirth-6", image `sagait/engine:latest` @ `81bdeea02e8e`, admin port 8443 → host 8449) — a dev-environment engine registered to the test-admin org, with channels deployed (channels don't affect the admin server's context assembly). | 2026-08-05 |

For the Stage 2 "after" run, keep the layout identical: run the migrated engine build against the
SAME container appdata/extension estate (swap the image or binary, keep the volume), then diff.

## `session-gate.sh` — Stage 3 session persistence

```
./session-gate.sh <postgres|mysql|sqlserver|oracle> <default|none>
```

Proves the JDBC session store survives the migration on a real database, end to end and
unattended. One run stands up a throwaway database container, then:

1. boots a **9.4 engine** (`sagait/engine:4.6.0-ubuntu`) against it with
   `server.api.sessionstore=true` — it creates the schema and a login writes a real session row;
2. restarts that engine and re-uses the cookie — proves the store works *before* the migration,
   so a later failure can't be blamed on a store that never worked;
3. stops it, boots the **Jetty 12 build** from `server/setup/` against the SAME database, and
   re-uses the SAME cookie — **this is the compatibility claim**: a row written by 9.4 read by 12;
4. restarts the Jetty 12 engine and re-uses the cookie again.

Any step failing prints `GATE FAIL <db>/<cache>` and stops. Derby is not scripted — it was
verified in place on the mirth-6 container (see the plan's Stage 3 table).

Notes worth keeping:
- The 9.4 side runs in Docker on ports 19080/19443; the Jetty 12 side runs locally from
  `server/setup/` on 18080/18443 and **edits that install's `conf/mirth.properties`** — it is
  build output, so this is disposable, but don't be surprised to find it pointed at MySQL.
- `database.driver` is always written (empty when unused): a previous run's driver otherwise
  lingers in the shared install and gets handed to the next database.
- **No `&` in JDBC URLs.** `engine-docker`'s entrypoint substitutes env vars with `sed` and
  escapes only `/`, so an unescaped `&` expands to the whole matched line and corrupts
  `mirth.properties`. That is a real engine-docker bug; the harness sidesteps it.
- SQL Server has no `MSSQL_DATABASE` env — the script creates `mirthdb` with `sqlcmd` first.
