# springboot_ebook

## Catalog schema

Catalog metadata uses UUID identities, distinct visibility/publication/serial states and revision counters.
Book version pointers are composite foreign keys and require the same book's READY version. READY versions
and their artifact identities are immutable. Stored-object rows describe hashes, sizes, types and keys;
they do not contain files. Actual file verification and spine/TOC invariants belong to the future ingest worker.
There is no upload/publish endpoint or runtime catalog seed in this schema step. A new installation remains empty.
Vietnamese search text is folded by the versioned `catalog_fold_v1` function, including đ/Đ. The GIN index supports
token search; it does not make arbitrary substring LIKE queries indexed.

## Session authentication

### Profile, password and session management

`GET/PATCH /api/v1/account` reads the profile and updates only displayName/bio (60/240 characters).
Email, role, verification and provider fields are read only; unknown patch fields are rejected.
`POST /api/v1/account/change-password` requires currentPassword/newPassword, rotates the current
session and revokes all old sessions/reset links. Refresh CSRF after the change. Google-only
accounts return PASSWORD_NOT_AVAILABLE and cannot create a local password through this endpoint.

`GET /api/v1/account/sessions` lists up to 100 recent owned sessions with opaque revocation IDs
and a current marker. `DELETE /api/v1/account/sessions/{id}` revokes only an owned session;
revoking the current session signs you out. Responses never return cookie/session secrets.
All mutations require CSRF; password reauthentication is rate limited.

To link Google to a password account, POST currentPassword to `/api/v1/account/google/link`
then navigate to the returned authorizationUrl. The five-minute link intent is bound to the
authenticated user and credential epoch, in addition to OIDC state/nonce/PKCE. Identity ownership,
account switching, expiry and uniqueness are checked atomically. Linking never changes email,
role or verification and never steals an identity already owned by another user. Unlinking,
email changes and adding a first password to Google-only accounts are not implemented.

### Registration and email verification

`POST /api/v1/auth/forgot-password` accepts an email and always returns the same neutral 202
body for eligible, unknown and Google-only accounts, including a mail-delivery failure.
Password accounts receive a 30-minute reset link. `POST /api/v1/auth/reset-password` accepts
the token and new password, atomically consumes it and revokes all application sessions.
Reset cannot create a password on Google-only accounts and never marks email verified as a side effect.
Both endpoints require CSRF and are rate limited; reset uses the same password policy as registration.

`POST /api/v1/auth/register` accepts email, displayName and password; it creates an unverified
USER and sends a single-use verification link. `POST /api/v1/auth/verify-email` consumes the
link token, while `POST /api/v1/auth/resend-verification` invalidates old verification tokens
and sends a new one. All mutations require CSRF; passwords use the same 12-character minimum
and 72-UTF-8-byte maximum policy. Unverified users cannot perform verified-only actions.
Registration/resend return neutral 202 messages; existing Google-only credentials are never overwritten.

Mail links use the configured frontend origin. Only SHA-256 token hashes are stored in PostgreSQL;
`FRONTEND_HASH_ROUTING=true` matches the current React HashRouter, so email links and Google
return redirects use `/#/verify-email`, `/#/login` and other hash routes. Set it false only for
a frontend deliberately migrated to BrowserRouter with the required server rewrite rules.
verification tokens expire after one hour and are consumed atomically. Local mail uses Mailpit
at `SMTP_HOST`/`SMTP_PORT`. A send failure rolls back token/account changes and reports an error.
SMTP delivery has finite connection/read/write timeouts. Mail is optional for process health by
default; set `MAIL_HEALTH_ENABLED=true` to include it in aggregate health. Production SMTP setup
and delivery retries/outbox are separate deployment work; do not enable SMTP debug logging.

For end-to-end SMTP tests, download Mailpit locally and run:

```powershell
.\tools\Test-LocalDatabase.ps1 -MailpitBin 'C:\tools\mailpit.exe'
```

The harness starts an isolated loopback Mailpit instance on temporary ports, verifies captured
mail through its API, and stops only its own processes. Without Mailpit, mail integration tests
are explicitly skipped; ordinary unit/session/migration tests still run. Mailpit's local inbox
contains test links and must remain in ignored runtime storage.

### Google sign-in

Google sign-in is optional and disabled by default. In Google Cloud, configure the OAuth
consent screen and a **Web application** OAuth client, add test users while the app is in
testing, and register an exact redirect URI. For a local Vite same-origin proxy, use
`http://localhost:5173/api/v1/login/oauth2/code/google`. Set `GOOGLE_LOGIN_ENABLED=true`,
`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` and `GOOGLE_REDIRECT_URI` in private backend
configuration. `APP_PUBLIC_ORIGIN` must be the frontend origin. Production requires HTTPS.
The callback URI is explicit configuration and never derived from untrusted Host headers.

Check `/api/v1/auth/capabilities` before showing the Google button. Navigate the browser to
`/api/v1/oauth2/authorization/google?returnTo=/account`; do not fetch this as a JSON request.
The backend performs the code exchange, validates the ID token and establishes the same
session used by password login. Refresh CSRF and `/api/v1/auth/me` after returning to the UI.
Only openid/profile/email are requested; Google access/refresh/ID tokens are not stored in
the database or application session. Cancel/failure redirects to the frontend login route with `error=GOOGLE_LOGIN_FAILED`.

Identities use Google's issuer and subject, never email. Matching emails are not automatically
linked. Google-only accounts have no password credential; password login fails normally.
Gmail/Workspace-authoritative emails may be marked verified; other Google email addresses
still require the application's email-verification flow. Claims never grant administrator roles.
The native database harness exercises a local OIDC provider/JWKS and negative-token cases.
A real Google browser test needs your Web client and test-user configuration; automated OIDC
tests do not establish that your Google Cloud configuration has been verified live.

Each authenticated request rechecks the current database role, status and credential epoch.
Locking/deleting a user or changing their password invalidates an old principal. Access changes
revoke JDBC sessions atomically and serialize administrator-set changes to protect the last
active administrator. Ownership policies do not grant administrators implicit access to another
user's private resources. Audit events accept typed actions and IDs only, without arbitrary text
or credentials. These policies are tested independently; an admin HTTP API is not exposed yet.

The API uses PostgreSQL-backed sessions, not browser JWTs. Call `GET /api/v1/auth/csrf`
with cookies enabled, then submit JSON `{ "email": "...", "password": "..." }` to
`POST /api/v1/auth/login` with the returned header name/token and the same cookie jar.
After login, fetch a new CSRF token; login rotates the session ID and clears the old token.
`GET /api/v1/auth/me` returns the current active user. `POST /api/v1/auth/logout`
requires CSRF and invalidates the server session. Never store credentials or session IDs
in browser storage. Sessions expire after 30 minutes of inactivity; cookies are HttpOnly,
SameSite=Lax and Secure in the production profile. Use a same-origin proxy for the frontend.

Login attempts are throttled atomically in PostgreSQL by hashed source IP and normalized
email. The API trusts the direct remote address, not arbitrary forwarded headers. A reverse
proxy deployment must explicitly configure its trusted forwarding boundary before release.
The local opt-in seed creates a USER only, using `APP_LOCAL_SEED_ENABLED`, `SEED_EMAIL`
and `SEED_PASSWORD` from private local configuration. No default login exists.

Run `tools/Test-LocalDatabase.ps1` on Windows with JDK 21 and native PostgreSQL to exercise
CSRF, login/logout, session fixation, throttle and session persistence across API restart.

Java backend for an EPUB reading application. The project includes an HTTP API and a separate headless worker process. EPUB ingestion and text-to-speech are still in development.

## Requirements

- JDK 21
- Spring Boot 4.1.1
- Maven 3.10.0, downloaded by the included Maven Wrapper 3.3.4
- PostgreSQL 17 or newer for local application startup

## Build

```sh
git clone https://github.com/phamvanduy1008/springboot_ebook.git
cd springboot_ebook
./mvnw clean verify
```

On Windows, set `JAVA_HOME` to your JDK 21 directory and use `mvnw.cmd clean verify`.

## Local configuration

Copy `.env.example` to `.env` in the repository root and supply your local settings. The file uses Java properties syntax; do not add shell `export` statements or quotes. Environment variables override file values. Never commit credentials.

Supported profiles are `local`, `test` and `prod`. Tests use isolated settings. Production startup validates required configuration and requires an HTTPS public origin.

Create an empty PostgreSQL database and a dedicated application role, then set `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`. Flyway applies identity migrations and Hibernate validates the schema. Do not point a new Flyway installation at a database already installed from a separate SQL script, and do not enable automatic baselining to bypass that check.

Local user seeding is disabled by default. It can be enabled explicitly in the local profile with `APP_LOCAL_SEED_ENABLED`, `SEED_EMAIL` and `SEED_PASSWORD`. It creates a regular user with a BCrypt hash, never a default administrator.

## Run

After building, run each process in its own terminal:

```sh
java -jar api/target/api-0.1.0-SNAPSHOT-exec.jar
java -jar worker/target/worker-0.1.0-SNAPSHOT-exec.jar
```

On Windows, use the Java executable under `%JAVA_HOME%/bin` if the Java version on PATH differs.

The API binds to `127.0.0.1:8080` by default. Check `/actuator/health`, `/actuator/health/liveness` and `/actuator/health/readiness`. The worker does not open an HTTP port. Stop each process with Ctrl+C.

## Modules

`core` contains shared models, services, repositories and integrations. `migrations` holds Flyway SQL resources; `api` and `worker` are independently packaged entry points. Neither executable depends on the other in production. Core is a regular library JAR.

The API uses Spring MVC with a layered Controller → Service → Repository design:

```text
api/       controller/auth, dto/auth, dto/common, security, exception, filter, config
core/      service/identity, repository/identity, entity/identity, model/identity,
           integration/mail, config
worker/    headless entry point using the same core services
migrations/Flyway SQL resources (unchanged by the architecture refactor)
contracts/openapi.yaml
```

Controllers handle HTTP validation and DTOs; security coordinates password/OIDC authentication and sessions. Core services own business rules and transaction boundaries. Repositories own SQL/JPA and database locks; mail delivery lives in an integration gateway. Core has no servlet or API dependencies, and entities are not returned as HTTP responses. Architecture tests enforce these boundaries.

Two deprecated records under `api.auth` are deserialization bridges for JDBC sessions written before the package move. Runtime code uses `api.security`; retain the bridges while older sessions or pending Google link requests may still exist. A fixture captured before the move checks this compatibility.

Use `tools/Test-LocalDatabase.ps1 -Clean -MailpitBin <path-to-mailpit.exe>` for a clean reactor verification against an isolated native database and mail server.

## Tests

`verify` runs unit, architecture and packaged-process smoke tests.

For native PostgreSQL integration tests on Windows, set `JAVA_HOME` to JDK 21 and run:

```powershell
.\tools\Test-LocalDatabase.ps1 -PostgresBin 'C:\Program Files\PostgreSQL\18\bin'
```

The script creates an isolated loopback cluster with temporary credentials, tests migrations, JPA and JDBC session persistence, then stops its processes and removes the credential file. Test data and logs remain under ignored `var/`. Without this harness, database integration tests are skipped.

## API contract

`contracts/openapi.yaml` is the source contract for health, session authentication and admin catalog endpoints. Admins manage public book metadata, categories, authors and draft version records under `/api/v1/admin`. Mutations require CSRF and updates require the current revision; private books are outside this API. Counts include only published public books with a READY published version.

Creating or cloning a book creates an UPLOADING version with no artifacts. Upload, ingestion and publication are not exposed yet. READY metadata fixtures exist only in integration tests; they do not verify or provide readable EPUB files.

Guests can query `/api/v1/books`, `/books/{id}`, `/books/filters`, `/categories`, `/categories/{id}/books` and `/home`. Public results consistently require PUBLIC/PUBLISHED and a READY published pointer. Search folds Vietnamese accents and đ/Đ. Sort fields are allowlisted and UUID breaks ties. Home featured means recently updated; suggestions and trending are empty until their real sources are available. No reader or file URL is claimed from metadata alone.

MVC errors use `application/problem+json` with `type`, `title`, `status`, `code`, `detail`, `requestId` and `fieldErrors`. Pagination DTOs use zero-based pages, sizes from 1 to 100, an endpoint-specific sort allowlist and an ID tie-breaker. OpenAPI and HTTP contract checks run during `verify`.

For local browser development, proxy `/api` to the backend and use relative URLs. Cross-origin credential access is not enabled globally.
