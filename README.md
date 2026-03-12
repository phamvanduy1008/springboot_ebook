# springboot_ebook

Java backend for an EPUB reading application. The project includes an HTTP API and a separate headless worker process. EPUB ingestion and text-to-speech are still in development.

## Requirements

- JDK 21
- Spring Boot 4.1.1
- Maven 3.10.0, downloaded by the included Maven Wrapper 3.3.4
- PostgreSQL 17 or newer for local application startup

## Build

```sh
git clone --branch feat/be-02-1-identity-db https://github.com/phamvanduy1008/springboot_ebook.git
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

`domain` contains plain Java models, `application` defines ports, `infrastructure` supplies adapters, `migrations` holds SQL resources, and `api`/`worker` are separate entry points.

## Tests

`verify` runs unit, architecture and packaged-process smoke tests.

For native PostgreSQL integration tests on Windows, set `JAVA_HOME` to JDK 21 and run:

```powershell
.\tools\Test-LocalDatabase.ps1 -PostgresBin 'C:\Program Files\PostgreSQL\18\bin'
```

The script creates an isolated loopback cluster with temporary credentials, tests migrations, JPA and JDBC session persistence, then stops its processes and removes the credential file. Test data and logs remain under ignored `var/`. Without this harness, database integration tests are skipped.
