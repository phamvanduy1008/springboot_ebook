CREATE TABLE audit_logs (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  actor_id uuid REFERENCES users(id) ON DELETE SET NULL,
  action varchar(80) NOT NULL,
  target_type varchar(40) NOT NULL,
  target_id uuid,
  request_id varchar(64) NOT NULL CHECK (request_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
  safe_metadata jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (safe_metadata = '{}'::jsonb),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_logs_recent_idx ON audit_logs(created_at DESC,id);
CREATE INDEX audit_logs_target_idx ON audit_logs(target_type,target_id,created_at DESC);
