-- Catalog metadata only. File ingestion, spine/TOC rows and publishing services follow separately.
CREATE FUNCTION catalog_fold_v1(value text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT lower(translate(regexp_replace(normalize(coalesce(value,''),NFD),U&'[\0300-\036f]','','g'),'đĐ','dd'))
$$;

CREATE TABLE authors (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name varchar(160) NOT NULL CHECK (length(btrim(name))>0),
  slug varchar(200) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  biography varchar(4000) NOT NULL DEFAULT '',
  revision bigint NOT NULL DEFAULT 0 CHECK (revision>=0),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE categories (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name varchar(100) NOT NULL CHECK (length(btrim(name))>0),
  slug varchar(120) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  group_name varchar(40) NOT NULL DEFAULT 'OTHER' CHECK (length(btrim(group_name))>0),
  description varchar(2000) NOT NULL DEFAULT '',
  color varchar(7) NOT NULL DEFAULT '#3156D9' CHECK (color ~ '^#[0-9a-fA-F]{6}$'),
  sort_order integer NOT NULL DEFAULT 0 CHECK (sort_order>=0),
  revision bigint NOT NULL DEFAULT 0 CHECK (revision>=0)
);
CREATE TABLE books (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id uuid REFERENCES users(id),
  created_by uuid REFERENCES users(id),
  visibility varchar(8) NOT NULL CHECK (visibility IN ('PUBLIC','PRIVATE')),
  status varchar(12) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','HIDDEN','DELETED')),
  serial_status varchar(12) NOT NULL DEFAULT 'UNKNOWN' CHECK (serial_status IN ('UNKNOWN','ONGOING','COMPLETED','HIATUS')),
  title varchar(300) NOT NULL CHECK (length(btrim(title))>0),
  author_display varchar(500) NOT NULL CHECK (length(btrim(author_display))>0),
  slug varchar(350) NOT NULL CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  summary varchar(2000) NOT NULL DEFAULT '',
  description varchar(20000) NOT NULL DEFAULT '',
  language varchar(35),
  is_translation boolean NOT NULL DEFAULT false,
  publication_year smallint CHECK (publication_year BETWEEN 1 AND 9999),
  search_text text GENERATED ALWAYS AS (catalog_fold_v1(title||' '||author_display)) STORED,
  search_fold_version varchar(40) NOT NULL DEFAULT 'vi-v1' CHECK (search_fold_version='vi-v1'),
  cover_key varchar(500) CHECK (cover_key IS NULL OR (cover_key !~ '(^/|\\|(^|/)\.\.?(/|$)|:|[?#])' AND length(cover_key)>0)),
  current_version_id uuid,
  published_version_id uuid,
  revision bigint NOT NULL DEFAULT 0 CHECK (revision>=0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz,
  CHECK ((visibility='PRIVATE' AND owner_id IS NOT NULL) OR (visibility='PUBLIC' AND owner_id IS NULL)),
  CHECK (visibility='PUBLIC' OR (status<>'PUBLISHED' AND published_version_id IS NULL)),
  CHECK (status<>'PUBLISHED' OR published_version_id IS NOT NULL),
  CHECK ((status='DELETED')=(deleted_at IS NOT NULL))
);
CREATE INDEX books_public_idx ON books(updated_at DESC,id DESC) WHERE visibility='PUBLIC' AND status='PUBLISHED';
CREATE INDEX books_owner_idx ON books(owner_id,updated_at DESC,id DESC) WHERE visibility='PRIVATE';
CREATE INDEX books_search_idx ON books USING gin(to_tsvector('simple',search_text));
CREATE TABLE book_authors (
  book_id uuid NOT NULL REFERENCES books(id) ON DELETE CASCADE,
  author_id uuid NOT NULL REFERENCES authors(id),
  position integer NOT NULL DEFAULT 0 CHECK (position>=0),
  PRIMARY KEY(book_id,author_id), UNIQUE(book_id,position)
);
CREATE INDEX book_authors_author_idx ON book_authors(author_id,book_id);
CREATE TABLE book_categories (
  book_id uuid NOT NULL REFERENCES books(id) ON DELETE CASCADE,
  category_id uuid NOT NULL REFERENCES categories(id),
  PRIMARY KEY(book_id,category_id)
);
CREATE INDEX book_categories_category_idx ON book_categories(category_id,book_id);
CREATE TABLE book_tags (
  book_id uuid NOT NULL REFERENCES books(id) ON DELETE CASCADE,
  tag varchar(80) NOT NULL CHECK (length(btrim(tag))>0),
  PRIMARY KEY(book_id,tag)
);
CREATE TABLE book_versions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  book_id uuid NOT NULL REFERENCES books(id),
  version_no integer NOT NULL CHECK (version_no>0),
  state varchar(12) NOT NULL DEFAULT 'UPLOADING' CHECK (state IN ('UPLOADING','PROCESSING','READY','FAILED')),
  source_object_id uuid,
  reader_object_id uuid,
  cover_object_id uuid,
  extractor_version varchar(80),
  sanitizer_version varchar(80),
  rights_note varchar(4000),
  ready_at timestamptz,
  failure_code varchar(80),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(book_id,version_no), UNIQUE(id,book_id),
  CHECK (state<>'READY' OR (source_object_id IS NOT NULL AND reader_object_id IS NOT NULL
    AND length(btrim(extractor_version))>0 AND extractor_version IS NOT NULL
    AND length(btrim(sanitizer_version))>0 AND sanitizer_version IS NOT NULL AND ready_at IS NOT NULL)),
  CHECK (source_object_id IS NULL OR reader_object_id IS NULL OR source_object_id<>reader_object_id)
);
-- These rows describe object identity, never file contents. A future worker verifies actual files.
CREATE TABLE stored_objects (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  book_id uuid NOT NULL REFERENCES books(id),
  book_version_id uuid NOT NULL,
  kind varchar(16) NOT NULL CHECK (kind IN ('SOURCE_EPUB','READER_EPUB','COVER','RESOURCE')),
  backend varchar(16) NOT NULL DEFAULT 'LOCAL' CHECK (backend IN ('LOCAL','S3')),
  object_key varchar(500) NOT NULL CHECK (length(object_key)>0 AND object_key !~ '(^/|\\|(^|/)\.\.?(/|$)|:|[?#])'),
  sha256 varchar(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  size_bytes bigint NOT NULL CHECK (size_bytes>0),
  media_type varchar(120) NOT NULL CHECK (length(btrim(media_type))>0),
  state varchar(12) NOT NULL DEFAULT 'STAGED' CHECK (state IN ('STAGED','READY','DELETING')),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(backend,object_key), UNIQUE(id,book_version_id,book_id),
  FOREIGN KEY(book_version_id,book_id) REFERENCES book_versions(id,book_id)
);
CREATE INDEX stored_objects_version_idx ON stored_objects(book_version_id,kind);
ALTER TABLE book_versions ADD CONSTRAINT version_source_fk FOREIGN KEY(source_object_id,id,book_id) REFERENCES stored_objects(id,book_version_id,book_id) DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE book_versions ADD CONSTRAINT version_reader_fk FOREIGN KEY(reader_object_id,id,book_id) REFERENCES stored_objects(id,book_version_id,book_id) DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE book_versions ADD CONSTRAINT version_cover_fk FOREIGN KEY(cover_object_id,id,book_id) REFERENCES stored_objects(id,book_version_id,book_id) DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE books ADD CONSTRAINT book_current_version_fk FOREIGN KEY(current_version_id,id) REFERENCES book_versions(id,book_id);
ALTER TABLE books ADD CONSTRAINT book_published_version_fk FOREIGN KEY(published_version_id,id) REFERENCES book_versions(id,book_id);

CREATE FUNCTION guard_catalog_version() RETURNS trigger LANGUAGE plpgsql AS $$
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
CREATE TRIGGER catalog_version_lifecycle BEFORE INSERT OR UPDATE OR DELETE ON book_versions FOR EACH ROW EXECUTE FUNCTION guard_catalog_version();

CREATE FUNCTION guard_catalog_book() RETURNS trigger LANGUAGE plpgsql AS $$
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
CREATE TRIGGER catalog_book_identity BEFORE INSERT OR UPDATE ON books FOR EACH ROW EXECUTE FUNCTION guard_catalog_book();

CREATE FUNCTION guard_catalog_object() RETURNS trigger LANGUAGE plpgsql AS $$
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
CREATE TRIGGER catalog_object_identity BEFORE UPDATE OR DELETE ON stored_objects FOR EACH ROW EXECUTE FUNCTION guard_catalog_object();
