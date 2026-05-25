CREATE TABLE IF NOT EXISTS quota_buckets (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),user_id uuid NOT NULL REFERENCES users(id),
 kind varchar(16) NOT NULL CHECK(kind IN ('STORAGE_BYTES','TTS_CHARACTERS')),
 window_start timestamptz NOT NULL,window_end timestamptz NOT NULL,
 limit_units bigint NOT NULL CHECK(limit_units>=0),used_units bigint NOT NULL DEFAULT 0 CHECK(used_units>=0),
 reserved_units bigint NOT NULL DEFAULT 0 CHECK(reserved_units>=0),revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
 UNIQUE(user_id,kind,window_start),UNIQUE(id,user_id),CHECK(window_end>window_start),CHECK(used_units<=limit_units-reserved_units)
);
CREATE TABLE IF NOT EXISTS quota_reservations (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),user_id uuid NOT NULL,bucket_id uuid NOT NULL,
 request_id uuid NOT NULL,amount_units bigint NOT NULL CHECK(amount_units>0),
 state varchar(12) NOT NULL DEFAULT 'RESERVED' CHECK(state IN ('RESERVED','COMMITTED','RELEASED')),
 created_at timestamptz NOT NULL DEFAULT now(),expires_at timestamptz NOT NULL,
 UNIQUE(user_id,request_id),FOREIGN KEY(bucket_id,user_id) REFERENCES quota_buckets(id,user_id),CHECK(expires_at>created_at)
);
CREATE INDEX IF NOT EXISTS quota_reservations_expiry_idx ON quota_reservations(expires_at) WHERE state='RESERVED';
ALTER TABLE quota_reservations ADD COLUMN IF NOT EXISTS book_version_id uuid REFERENCES book_versions(id);
CREATE UNIQUE INDEX IF NOT EXISTS quota_ingest_version_idx ON quota_reservations(book_version_id) WHERE book_version_id IS NOT NULL;
CREATE TABLE epub_upload_receipts (
 user_id uuid NOT NULL REFERENCES users(id),mutation_key uuid NOT NULL,fingerprint varchar(64) NOT NULL,
 book_id uuid NOT NULL REFERENCES books(id),version_id uuid NOT NULL REFERENCES book_versions(id),job_id uuid NOT NULL REFERENCES jobs(id),
 book_revision bigint NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(user_id,mutation_key)
);
