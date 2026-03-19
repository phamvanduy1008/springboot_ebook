CREATE TABLE auth_rate_limits (
  bucket_key varchar(64) PRIMARY KEY CHECK (bucket_key ~ '^[0-9a-f]{64}$'),
  window_started timestamptz NOT NULL,
  hits integer NOT NULL CHECK (hits BETWEEN 1 AND 1000000)
);
CREATE INDEX auth_rate_limits_expiry_idx ON auth_rate_limits(window_started);
