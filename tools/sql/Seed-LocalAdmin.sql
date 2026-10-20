-- Run through tools/Seed-LocalAdmin.ps1. psql variables keep the initial password out of this file.
\set ON_ERROR_STOP on
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Refuse to replace a real user's password or silently rotate an existing admin.
CREATE TEMP TABLE admin_seed_guard (existing_count integer NOT NULL CHECK (existing_count = 0)) ON COMMIT DROP;
INSERT INTO admin_seed_guard
SELECT count(*) FROM users WHERE email_normalized = lower(:'admin_email');

INSERT INTO users (email_normalized, password_hash, display_name, role, status, email_verified_at)
VALUES (lower(:'admin_email'), crypt(:'admin_password', gen_salt('bf', 12)),
        'Local Administrator', 'ADMIN', 'ACTIVE', clock_timestamp());
COMMIT;

SELECT 'SEED_OK|' || email_normalized || '|' ||
       CASE WHEN password_hash = crypt(:'admin_password', password_hash) THEN 'true' ELSE 'false' END
FROM users WHERE email_normalized = lower(:'admin_email') AND role = 'ADMIN' AND status = 'ACTIVE';
