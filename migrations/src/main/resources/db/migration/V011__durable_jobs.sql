-- Adopt the matching legacy job table without deleting queued work.
CREATE TABLE IF NOT EXISTS jobs (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), owner_id uuid REFERENCES users(id),
 book_id uuid REFERENCES books(id), book_version_id uuid,
 type varchar(16) NOT NULL CHECK(type IN ('INGEST','TTS','CLEANUP')),
 dedupe_key varchar(200) NOT NULL UNIQUE,
 payload_json jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(payload_json)='object' AND pg_column_size(payload_json)<=65536),
 status varchar(12) NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
 priority integer NOT NULL DEFAULT 0, attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
 max_attempts integer NOT NULL DEFAULT 3 CHECK(max_attempts BETWEEN 1 AND 100),
 available_at timestamptz NOT NULL DEFAULT now(), lease_until timestamptz, lease_token uuid,
 last_error_code varchar(80), created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(book_version_id,book_id) REFERENCES book_versions(id,book_id),
 CHECK(book_version_id IS NULL OR book_id IS NOT NULL), CHECK(attempts<=max_attempts),
 CHECK((status='RUNNING' AND lease_until IS NOT NULL AND lease_token IS NOT NULL)
    OR (status<>'RUNNING' AND lease_until IS NULL AND lease_token IS NULL))
);
CREATE INDEX IF NOT EXISTS jobs_claim_idx ON jobs(priority DESC,available_at,id) WHERE status='QUEUED';
CREATE INDEX IF NOT EXISTS jobs_owner_idx ON jobs(owner_id,created_at DESC);
CREATE INDEX IF NOT EXISTS jobs_running_lease_idx ON jobs(lease_until) WHERE status='RUNNING';
