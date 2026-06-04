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

`GET /api/v1/rankings` and `/rankings/authors` aggregate calendar week/month/year in UTC with exclusive end boundaries. UUID breaks ties and author credits remain separate identities. Views come only from CSRF-protected `POST /books/{id}/views` (empty body), deduplicated per book/viewer/day UTC and throttled per hashed remote address. No raw IP or user agent is stored. GET does not record a view. Read events await progress ingestion; likes come from current favorites added in the selected UTC window; reviews have no source yet, so ratings are null and rating rankings empty. Home trending uses actual monthly views.

`GET /api/v1/books/{id}/overview` combines public metadata, book-specific quote/characters, monthly metrics/rank and related public books. GET has no view-count side effect. `chapterCount` comes only from the published version's ingestion metadata and remains null when unknown. Admin quote and ordered character updates use `/admin/books/{id}/editorial` and `/characters`, guarded by CSRF, admin policy, book revision and audit. Existing character IDs must belong to the edited book. No chapter, comment or rating data is invented.

MVC errors use `application/problem+json` with `type`, `title`, `status`, `code`, `detail`, `requestId` and `fieldErrors`. Pagination DTOs use zero-based pages, sizes from 1 to 100, an endpoint-specific sort allowlist and an ID tie-breaker. OpenAPI and HTTP contract checks run during `verify`.

For local browser development, proxy `/api` to the backend and use relative URLs. Cross-origin credential access is not enabled globally.

## Existing Ebook database on PostgreSQL 18

Keep local settings in the ignored root `.env`: `DB_URL=jdbc:postgresql://127.0.0.1:5432/Ebook`, `DB_USERNAME=postgres`, and the local password when authentication requires one. Run from this repository root so both executables load `.env`. Hibernate validates; it never creates or updates the schema.

The historical `epub_schema.sql` public baseline differs from V001–V007. For that inspected schema only, after validating a restored backup, run `tools/Start-Local.ps1 -Database Ebook -AdoptLegacySchema`. The launcher performs a read-only schema preflight and creates a custom-format backup under ignored `var/`, then explicitly records Flyway BASELINE 7 and applies V008. This is not a record that V001–V007 executed. Normal configuration retains `baseline-on-migrate=false`; later starts use `tools/Start-Local.ps1 -Database Ebook` without adoption.

V008 preserves book UUIDs, titles, credits, category links and legacy metric rows/timestamps/keys. It adds missing auth/config fields and maintains folded search on legacy ordinary columns. Original READY/spine/TOC guards remain; current identity guards are added alongside them. Existing DRAFT metadata stays DRAFT and is not made readable or public through discovery. Do not rerun the old schema SQL over the adopted database. Keep backups private and outside Git.

## Personal library and settings

Authenticated users manage metadata through `/api/v1/library`, `/favorites`, `/view-history`
and `/settings`. Lists are paginated (zero-based, size 1..100), use `q`, `sort=recent|title|author`,
`shelf=all|want|reading|finished` and `ownership=all|owned|public`. Counts reflect all accessible
personal state, independent of the current list filters. PRIVATE means ownership, never a shelf.
Public discoverable metadata and owned private metadata are eligible; administrators gain no
implicit access to another account's private books. Unavailable titles disappear from list/count
responses, but users may remove their own stale relations.

Read `/library/{bookId}` before changing a book. All shelf/favorite/history mutations share its
revision. PUT `/library/{bookId}` accepts `{ "shelf": "want", "revision": 0 }`; PUT
`/favorites/{bookId}` and POST `/view-history/{bookId}` accept `{ "revision": 0 }`.
DELETE on these item paths takes `?revision=N`. Return values include the new shared state and
revision. Every mutation also requires CSRF and a client-generated UUID `Idempotency-Key` header.
Keep that key and exact request for a retry: for 24 hours it returns the original result without
reapplying the operation. A different request with the same key returns IDEMPOTENCY_CONFLICT;
a stale revision returns REVISION_CONFLICT. Refetch state before making another edit. No automatic
migration from browser localStorage occurs.

Favorites do not require a shelf and removing a shelf preserves favorites, books and ownership.
History records only an accessible metadata view explicitly confirmed through POST, deduplicates
by user/book and retains the 100 most recently viewed titles (server timestamp, UUID tie-break).
It is independent of reading/listening progress. These APIs never return fabricated progress,
chapter locations, file paths or reader access.

Likes count currently active favorites whose last false-to-true transition falls inside the
selected calendar UTC window, from currently active accounts, restricted to public catalog books.
Repeated saves/favorites and idempotent retries cannot increment that count; unfavoriting removes
the contribution. Likes rankings omit zero-like rows. Author credits follow existing UUID rules.

Settings GET returns defaults until saved. PUT replaces only theme, readerTheme, fontSize,
lineHeight, readerFont and revision; use the same CSRF/idempotency rules. Unknown fields (including
role, user ID and paths) are rejected. See OpenAPI for exact allowlists and bounds.

# Private local object storage

The API and worker share `STORAGE_ROOT` (default `./var/storage`, resolved from
their working directory). Set the same absolute path for both processes. No
cloud account is needed. Keep this directory outside every web server document
root; grant filesystem access only to the service account. Never mount it as a
static resource directory or commit its contents.

`ObjectStorage` stages bounded streams, computes SHA-256/byte size, verifies them
before atomic promotion to an immutable server-generated object identity, and
supports reopen/stat/idempotent deletion. Staging and objects must reside on the
same filesystem with atomic rename support. Symlinks, traversal and arbitrary
paths are rejected. Filesystem administrators remain trusted; concurrent hostile
changes to parent directories are outside the local adapter's threat boundary.

The staging sweeper may remove incomplete files older than 48 hours (uploads must
finish within that window). Promoted objects must only be cleaned after checking
database references; no age-only deletion of permanent objects is permitted.
Storage is private by default; delivery must resolve server object IDs and check
book/version permissions. A later S3/R2 adapter can implement the same interface;
bucket credentials, public buckets and CDN deployment are deferred.

## Durable jobs

PostgreSQL jobs use a permanent server-generated dedupe key, SKIP LOCKED claims,
90-second leases and a fresh fencing token for every attempt. Heartbeats run every
20 seconds. A result transaction locks and validates the live lease before writing
results and rechecks it at completion; an expired worker cannot commit results.
Expired leases requeue with bounded backoff, or fail after max attempts. No raw
exception message or job payload is returned by `GET /api/v1/jobs/{id}`.

The separate worker polls only registered handlers, stops taking new jobs on
shutdown and leaves interrupted work to lease recovery. No parser/TTS handler is
installed by the queue prerequisite. A public administrator job has no owner;
private jobs remain accessible only to their owner, including when another
administrator requests them. Run `JobQueueIT` against an isolated native
PostgreSQL database with `EPUB_IDENTITY_DB_TEST=true` and the standard DB variables.

## EPUB compatibility probe

`EpubExtractor` is an unwired compatibility probe using JDK 21 XML APIs and Apache
Commons Compress 1.28.0. It emits a deterministic sanitized reader EPUB, a flat
parent-indexed TOC, ordered spine resources and canonical raw text. The EPUB2 NCX
and EPUB3 nav fixtures are self-authored CC0, including a generated test font.
No downloaded books are part of the tests or Git history.

Generate the corpus with `mvnw test -pl core -am -Dtest=EpubExtractorTest
-Dsurefire.failIfNoSpecifiedTests=false -Depub.spike.output=<absolute-path>/var/epub-spike`.
For browser QA install ephemeral dependencies using
`npm install --prefix var/epub-renderer --ignore-scripts --no-audit --no-fund --save-exact
epubjs@0.3.93 jszip@3.10.1 @playwright/test@1.64.1`, then run
`node tools/Check-EpubCompatibility.cjs`. The local server binds only to loopback,
checks embedded image/font loading and nested non-ASCII fragment navigation,
rejects external requests and writes JSON/screenshots into `var/epub-spike`.
Windows uses installed Edge; set `EPUB_QA_BROWSER_CHANNEL` for another browser.
`EPUB_QA_NODE_MODULES`, `EPUB_QA_PLAYWRIGHT_MODULE` and `EPUB_SPIKE_DIR` can point to
existing test dependencies/output. These JavaScript packages are QA tools only.

Supported scope is reflowable EPUB2/3. DRM/encryption and fixed layouts are
unsupported. Scripts/forms/remote URLs and SVG are removed; CSS uses a small
property allowlist, with validated local font-face links. Complex CSS, external
fonts and layouts are not preserved. Unicode fragments stay literal in XML href
attributes because the tested ePub.js version looks up IDs without URI decoding.
This probe does not install an upload route or production ingest handler.

## Local EPUB upload

Send the file as a raw `application/epub+zip` body, with session cookie,
`X-CSRF-TOKEN` and UUID `Idempotency-Key`. The maximum is 64 MiB; both declared
length and streamed bytes are checked. A bounded directory/mimetype probe checks
the EPUB envelope without parsing XML or inflating the book's other resources.

* `POST /api/v1/admin/books/{bookId}/epub?revision=N` stages a public book version
  for an administrator; optional `rightsNote` records the distribution basis.
* `POST /api/v1/imports?title=...&author=...` creates a private book for the verified
  session user. `PUT /api/v1/library/books/{bookId}/epub?revision=N` stages a new
  version of that user's existing private book. Administrators have no override.

Successful requests return 202 with book/version/job IDs and the updated book
revision. Poll the Location job URL. Upload does not parse or publish the book.
Replay the identical key/body/parameters for the original response; changed
requests conflict. Failed transactions/replays clean their unreferenced files.

`EPUB_PRIVATE_QUOTA_BYTES` defaults to 1 GiB, `EPUB_ADMIN_QUOTA_BYTES` to 100 GiB,
per account across uploads, with at most three processing private versions per
owner. These are logical limits, not a promise of available local disk. Source
bytes are reserved transactionally on upload; artifact accounting/finalization
belongs to the ingest handler. Increasing configuration does not silently reset
existing quota buckets. Raw request bodies are never logged. Multipart uploads,
cloud URLs and client-provided storage paths are not supported.

The production extractor now preflights the ZIP32 directory count before archive
allocation, caps aggregate XML elements at 500000 and discards each DOM after
deriving text/anchors. It accepts the exact inert standard XHTML/NCX DOCTYPE
declarations after stripping them; internal subsets and every other DTD remain
forbidden, and no resolver can access files or the network. ZIP64/multidisk is
unsupported. Legacy inputs may put/compress mimetype elsewhere in the archive;
the output always normalizes it to the first uncompressed entry.
Spine and TOC persist separately, including nested parents and multiple fragments
per file. READY reader structure is immutable; raw text remains UTF-8 in storage
with UTF-16 string offsets/lengths, independently of any future TTS normalization.

## Local ingest worker

Run the packaged API and headless worker against the same database and absolute
`STORAGE_ROOT`. On Windows, after building, use `tools/Start-WorkerLocal.ps1
-Database Ebook`; it starts hidden with a 1536 MiB heap and records its PID/log
directory. Confirm `Started WorkerApplication` in the log before relying on it.
On any OS use `java -Xmx1536m -jar worker/target/worker-0.1.0-SNAPSHOT-exec.jar`
with the standard DB/storage environment. `EPUB_INGEST_ENABLED=false` disables
the handler for maintenance. This is an independent process; API startup alone
does not process queued uploads.

The handler verifies source integrity, extracts once, promotes and checks all
reader/resource/raw-text objects, then persists them under the live job lease.
Only that transaction makes the immutable version READY and advances the current
pointer when its version number is newer. It never publishes the book or starts
TTS. Source plus expanded artifacts are charged to the account's lifetime quota.
Combined sanitized resource/raw-text output is also capped at 256 MiB.

Permanent EPUB errors fail without retry; transient errors retry with the queue's
backoff. Terminal failures release quota and remove copied failed sources after
commit. Prepared files from a lost lease are discarded if unreferenced. Worker
maintenance reconciles terminal crash jobs every minute and scans 48-hour-old
staging/orphan objects hourly, checking DB references before deletion. Promotion
refreshes mtime; upload/result transactions have a 120-second timeout. Original
files outside storage are never removed. Artifact/TOC/resource rows cannot be
appended or edited after READY; referenced historical versions are retained.

For existing local books, the worker also has a trusted operator import mode,
enabled only in the local profile. A UTF-8 TSV manifest contains `book-UUID`, TAB,
and an EPUB path relative to one explicit root. It never discovers arbitrary host
directories or exposes paths through HTTP. Add these worker arguments:
`--app.local-import.enabled=true --app.local-import.root=<absolute-source-root>
--app.local-import.manifest=<absolute-tsv> --app.local-import.actor=<active-admin-UUID>
--app.local-import.limit=1`. The default limit is one, maximum 2000. The operator
already needs DB/server access; normal upload/admin/epoch/quota policies still
apply. Sources outside the root, symlinks and other file types are rejected.
Matching pending/READY source hashes for this extractor version are skipped.
Imports enqueue ingestion and leave publication to a separate administrator
action. The original files and existing frontend are preserved.

## Publishing verified versions

`POST /api/v1/admin/books/{bookId}/publish` accepts `{versionId, revision}` with
session, CSRF and UUID Idempotency-Key. It locks the administrator/book, verifies
that the version is READY and belongs to that public book, then checks every
stored file's actual hash and size before setting the published pointer. It never
turns PROCESSING/FAILED/UPLOADING into READY or converts a private book to public.

`POST /api/v1/admin/books/{bookId}/unpublish` accepts `{revision}` with the same
headers and moves the book to HIDDEN, clearing its published pointer. Both actions
use revision checks, transactionally recorded idempotency receipts and audit.
Retries return the original result without replaying the action; refetch current
book state after a historical replay. Hiding/deleting metadata never removes
historical READY artifacts or references to progress/audio. Missing/tampered files
block publication until restored; there is no unsafe force-publish option.
