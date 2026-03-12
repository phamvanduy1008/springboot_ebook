-- PostgreSQL 17+ baseline. No application users/passwords are seeded.
DO $$ BEGIN
  IF current_setting('server_version_num')::integer < 170000 THEN
    RAISE EXCEPTION 'PostgreSQL 17 or newer is required';
  END IF;
END $$;

CREATE DOMAIN sha256_hex AS varchar(64) CHECK (VALUE ~ '^[0-9a-f]{64}$');

CREATE TABLE users (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email_normalized varchar(320) NOT NULL UNIQUE,
  password_hash varchar(255) NOT NULL,
  display_name varchar(60) NOT NULL CHECK (length(btrim(display_name)) BETWEEN 1 AND 60),
  bio varchar(240) NOT NULL DEFAULT '',
  role varchar(16) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER','ADMIN')),
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','LOCKED','DELETING','DELETED')),
  email_verified_at timestamptz,
  password_changed_at timestamptz NOT NULL DEFAULT now(),
  revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (email_normalized = lower(btrim(email_normalized)) AND position('@' IN email_normalized) > 1),
  CHECK (length(password_hash) > 0)
);

CREATE TABLE auth_tokens (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash sha256_hex NOT NULL UNIQUE,
  purpose varchar(24) NOT NULL CHECK (purpose IN ('VERIFY_EMAIL','RESET_PASSWORD')),
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz,
  CHECK (expires_at > created_at),
  CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);
CREATE INDEX auth_tokens_user_purpose_idx ON auth_tokens(user_id,purpose,expires_at);
CREATE INDEX auth_tokens_expiry_idx ON auth_tokens(expires_at) WHERE consumed_at IS NULL;

-- Compatible table shape from Spring Session's official PostgreSQL DDL.
-- https://github.com/spring-projects/spring-session/blob/main/spring-session-jdbc/src/main/resources/org/springframework/session/jdbc/schema-postgresql.sql
-- Recheck against the pinned dependency before enabling JDBC sessions in BE-03.1.
CREATE TABLE spring_session (
  primary_id char(36) NOT NULL PRIMARY KEY,
  session_id char(36) NOT NULL,
  creation_time bigint NOT NULL,
  last_access_time bigint NOT NULL,
  max_inactive_interval integer NOT NULL,
  expiry_time bigint NOT NULL,
  principal_name varchar(100)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session(session_id);
CREATE INDEX spring_session_ix2 ON spring_session(expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session(principal_name);
CREATE TABLE spring_session_attributes (
  session_primary_id char(36) NOT NULL REFERENCES spring_session(primary_id) ON DELETE CASCADE,
  attribute_name varchar(200) NOT NULL,
  attribute_bytes bytea NOT NULL,
  PRIMARY KEY(session_primary_id,attribute_name)
);
CREATE TABLE user_session_metadata (
  session_primary_id char(36) PRIMARY KEY REFERENCES spring_session(primary_id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_label varchar(120),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX user_session_metadata_user_idx ON user_session_metadata(user_id);
