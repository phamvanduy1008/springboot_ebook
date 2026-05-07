-- Forward compatibility for the explicitly adopted legacy public schema.
-- Existing Flyway installations already have these structures; no automatic baseline is enabled.
DO $$ BEGIN
 IF to_regclass('catalog_metric_config') IS NULL THEN
  IF to_regclass('books') IS NULL OR to_regclass('spring_session') IS NULL
   OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='book_interactions' AND column_name='dedupe_key')
   OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='book_interactions' AND column_name='happened_at')
   OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='users' AND column_name='email_normalized') THEN
   RAISE EXCEPTION 'Unsupported legacy schema: explicit inspection and backup required';
  END IF;
CREATE TABLE IF NOT EXISTS auth_rate_limits (
  bucket_key varchar(64) PRIMARY KEY CHECK (bucket_key ~ '^[0-9a-f]{64}$'),
  window_started timestamptz NOT NULL,
  hits integer NOT NULL CHECK (hits BETWEEN 1 AND 1000000)
);
CREATE INDEX IF NOT EXISTS auth_rate_limits_expiry_idx ON auth_rate_limits(window_started);

ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
CREATE TABLE IF NOT EXISTS user_identities (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider varchar(16) NOT NULL CHECK (provider='GOOGLE'),
  issuer varchar(255) NOT NULL CHECK (issuer='https://accounts.google.com'),
  subject varchar(255) NOT NULL CHECK (length(subject)>0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(provider,issuer,subject),
  UNIQUE(user_id,provider)
);


CREATE TABLE catalog_metric_config(singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),key_material bytea NOT NULL CHECK(octet_length(key_material)=32));
INSERT INTO catalog_metric_config(singleton,key_material) VALUES(true,decode(replace(gen_random_uuid()::text||gen_random_uuid()::text,'-',''),'hex'));
 END IF;
END $$;

ALTER TABLE authors ADD COLUMN IF NOT EXISTS revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0);
UPDATE authors SET biography='' WHERE biography IS NULL;
ALTER TABLE authors ALTER COLUMN biography SET DEFAULT '';
ALTER TABLE authors ALTER COLUMN biography SET NOT NULL;
ALTER TABLE categories ADD COLUMN IF NOT EXISTS color varchar(7) NOT NULL DEFAULT '#3156D9' CHECK(color ~ '^#[0-9a-fA-F]{6}$');
ALTER TABLE books ADD COLUMN IF NOT EXISTS cover_key varchar(500) CHECK(cover_key IS NULL OR (length(cover_key)>0 AND cover_key !~ '(^/|\\|(^|/)\.\.?(/|$)|:|[?#])'));
ALTER TABLE book_versions ADD COLUMN IF NOT EXISTS chapter_count integer CHECK(chapter_count>=0);
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='audit_logs' AND column_name='request_id' AND data_type='uuid') THEN
  ALTER TABLE audit_logs ALTER COLUMN request_id TYPE varchar(64) USING request_id::text;
 END IF;
 IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='book_characters' AND column_name='role_label') THEN
  ALTER TABLE book_characters RENAME COLUMN role_label TO role;
  ALTER TABLE book_characters ALTER COLUMN role SET DEFAULT '';
  UPDATE book_characters SET initials=coalesce(initials,''),color=coalesce(color,'#3343db');
  ALTER TABLE book_characters ALTER COLUMN initials SET DEFAULT '';
  ALTER TABLE book_characters ALTER COLUMN initials SET NOT NULL;
  ALTER TABLE book_characters ALTER COLUMN color SET DEFAULT '#3343db';
  ALTER TABLE book_characters ALTER COLUMN color SET NOT NULL;
 END IF;
END $$;
CREATE OR REPLACE FUNCTION catalog_fold_v1(value text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT lower(translate(regexp_replace(normalize(coalesce(value,''),NFD),U&'[\0300-\036f]','','g'),'đĐ','dd'))
$$;


CREATE FUNCTION sync_legacy_catalog_search() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 NEW.search_text=catalog_fold_v1(NEW.title||' '||NEW.author_display);RETURN NEW;
END $$;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='books' AND column_name='search_text' AND is_generated='NEVER') THEN
  CREATE TRIGGER catalog_search_refresh BEFORE INSERT OR UPDATE OF title,author_display ON books FOR EACH ROW EXECUTE FUNCTION sync_legacy_catalog_search();
  UPDATE books SET search_text=catalog_fold_v1(title||' '||author_display) WHERE search_text IS DISTINCT FROM catalog_fold_v1(title||' '||author_display);
 END IF;
END $$;
-- Preserve legacy dedupe keys, timestamps, rows and the daily-metrics view. New writes
-- synchronize both column sets; the new daily unique key rejects ambiguous old repeats.
CREATE FUNCTION sync_legacy_metric_columns() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 NEW.occurred_at=coalesce(NEW.occurred_at,NEW.happened_at);
 NEW.happened_at=NEW.occurred_at;
 NEW.viewer_hash=coalesce(NEW.viewer_hash,NEW.dedupe_key::text);
 NEW.dedupe_key=coalesce(NEW.dedupe_key,encode(sha256(convert_to(NEW.viewer_hash||':'||(NEW.occurred_at AT TIME ZONE 'UTC')::date::text,'UTF8')),'hex'));
 RETURN NEW;
END $$;
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='book_interactions' AND column_name='viewer_hash') THEN
  ALTER TABLE book_interactions ADD COLUMN viewer_hash char(64) CHECK(viewer_hash ~ '^[0-9a-f]{64}$');
  ALTER TABLE book_interactions ADD COLUMN occurred_at timestamptz;
  UPDATE book_interactions SET viewer_hash=dedupe_key,occurred_at=happened_at;
  ALTER TABLE book_interactions ALTER COLUMN viewer_hash SET NOT NULL;
  ALTER TABLE book_interactions ALTER COLUMN occurred_at SET NOT NULL;
  ALTER TABLE book_interactions ADD COLUMN event_day date GENERATED ALWAYS AS ((occurred_at AT TIME ZONE 'UTC')::date) STORED;
  ALTER TABLE book_interactions ADD CONSTRAINT catalog_metric_daily_unique UNIQUE(book_id,kind,viewer_hash,event_day);
  CREATE TRIGGER catalog_metric_compat BEFORE INSERT OR UPDATE ON book_interactions FOR EACH ROW EXECUTE FUNCTION sync_legacy_metric_columns();
 END IF;
END $$;
CREATE INDEX IF NOT EXISTS book_interactions_window_idx ON book_interactions(occurred_at,book_id,kind);
-- Keep the legacy spine/TOC/artifact guards; add current catalog identity checks alongside them.
CREATE OR REPLACE FUNCTION guard_catalog_version() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE object_row stored_objects%ROWTYPE;
BEGIN
  IF TG_OP='DELETE' THEN
    IF OLD.state='READY' AND EXISTS(SELECT 1 FROM books WHERE id=OLD.book_id AND status<>'DELETED') THEN
      RAISE EXCEPTION 'READY version is immutable' USING ERRCODE='23514';
    END IF;
    RETURN OLD;
  END IF;
  IF TG_OP='UPDATE' AND OLD.state='READY' AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION 'READY version is immutable' USING ERRCODE='23514';
  END IF;
  IF TG_OP='UPDATE' AND (NEW.id,NEW.book_id,NEW.version_no) IS DISTINCT FROM (OLD.id,OLD.book_id,OLD.version_no) THEN
    RAISE EXCEPTION 'Version identity is immutable' USING ERRCODE='23514';
  END IF;
  IF NEW.state='READY' THEN
    IF TG_OP='INSERT' THEN RAISE EXCEPTION 'Create a processing version before its artifacts' USING ERRCODE='23514'; END IF;
    SELECT * INTO object_row FROM stored_objects WHERE id=NEW.source_object_id AND book_id=NEW.book_id AND book_version_id=NEW.id FOR SHARE;
    IF NOT FOUND OR object_row.kind<>'SOURCE_EPUB' OR object_row.state<>'READY' THEN
      RAISE EXCEPTION 'Verified source metadata is required' USING ERRCODE='23514';
    END IF;
    SELECT * INTO object_row FROM stored_objects WHERE id=NEW.reader_object_id AND book_id=NEW.book_id AND book_version_id=NEW.id FOR SHARE;
    IF NOT FOUND OR object_row.kind<>'READER_EPUB' OR object_row.state<>'READY' THEN
      RAISE EXCEPTION 'Verified reader metadata is required' USING ERRCODE='23514';
    END IF;
    IF NEW.cover_object_id IS NOT NULL THEN
      SELECT * INTO object_row FROM stored_objects WHERE id=NEW.cover_object_id AND book_id=NEW.book_id AND book_version_id=NEW.id FOR SHARE;
      IF NOT FOUND OR object_row.kind<>'COVER' OR object_row.state<>'READY' THEN
        RAISE EXCEPTION 'Invalid cover metadata' USING ERRCODE='23514';
      END IF;
    END IF;
  END IF;
  RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS catalog_version_lifecycle ON book_versions;
CREATE TRIGGER catalog_version_lifecycle BEFORE INSERT OR UPDATE OR DELETE ON book_versions FOR EACH ROW EXECUTE FUNCTION guard_catalog_version();

CREATE OR REPLACE FUNCTION guard_catalog_book() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE version_id uuid;
BEGIN
  IF TG_OP='UPDATE' AND (NEW.id,NEW.visibility,NEW.owner_id) IS DISTINCT FROM (OLD.id,OLD.visibility,OLD.owner_id) THEN
    RAISE EXCEPTION 'Book identity and ownership are immutable' USING ERRCODE='23514';
  END IF;
  FOREACH version_id IN ARRAY ARRAY[NEW.current_version_id,NEW.published_version_id] LOOP
    IF version_id IS NOT NULL THEN
      PERFORM 1 FROM book_versions WHERE id=version_id AND book_id=NEW.id AND state='READY' FOR SHARE;
      IF NOT FOUND THEN RAISE EXCEPTION 'Book pointer requires its own READY version' USING ERRCODE='23514'; END IF;
    END IF;
  END LOOP;
  RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS catalog_book_identity ON books;
CREATE TRIGGER catalog_book_identity BEFORE INSERT OR UPDATE ON books FOR EACH ROW EXECUTE FUNCTION guard_catalog_book();

CREATE OR REPLACE FUNCTION guard_catalog_object() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE version_ready boolean; book_deleted boolean;
BEGIN
  SELECT state='READY' INTO version_ready FROM book_versions WHERE id=OLD.book_version_id FOR SHARE;
  SELECT status='DELETED' INTO book_deleted FROM books WHERE id=OLD.book_id;
  IF TG_OP='UPDATE' AND (NEW.id,NEW.book_id,NEW.book_version_id,NEW.kind,NEW.backend,NEW.object_key,NEW.sha256,NEW.size_bytes,NEW.media_type)
      IS DISTINCT FROM (OLD.id,OLD.book_id,OLD.book_version_id,OLD.kind,OLD.backend,OLD.object_key,OLD.sha256,OLD.size_bytes,OLD.media_type) THEN
    RAISE EXCEPTION 'Object identity is immutable' USING ERRCODE='23514';
  END IF;
  IF version_ready AND NOT book_deleted AND (TG_OP='DELETE' OR NEW IS DISTINCT FROM OLD) THEN
    RAISE EXCEPTION 'READY artifact is immutable' USING ERRCODE='23514';
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS catalog_object_identity ON stored_objects;
CREATE TRIGGER catalog_object_identity BEFORE UPDATE OR DELETE ON stored_objects FOR EACH ROW EXECUTE FUNCTION guard_catalog_object();
