ALTER TABLE stored_objects DROP CONSTRAINT IF EXISTS stored_objects_kind_check;
ALTER TABLE stored_objects ADD CONSTRAINT stored_objects_kind_check CHECK(kind IN ('SOURCE_EPUB','READER_EPUB','COVER','RAW_TEXT','RESOURCE','AUDIO'));
ALTER TABLE stored_objects DROP CONSTRAINT IF EXISTS stored_objects_size_bytes_check;
ALTER TABLE stored_objects ADD CONSTRAINT stored_objects_size_bytes_check CHECK(size_bytes>=0);
CREATE TABLE IF NOT EXISTS spine_items (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),book_version_id uuid NOT NULL,book_id uuid NOT NULL,
 spine_index integer NOT NULL CHECK(spine_index>=0),href text NOT NULL CHECK(href ~ '^EPUB/r[0-9]+\.xhtml$'),
 title varchar(500),is_linear boolean NOT NULL DEFAULT true,raw_text_object_id uuid NOT NULL,raw_utf16_length integer NOT NULL CHECK(raw_utf16_length>=0),
 UNIQUE(book_version_id,spine_index),UNIQUE(book_version_id,href),UNIQUE(id,book_version_id,book_id),
 FOREIGN KEY(book_version_id,book_id) REFERENCES book_versions(id,book_id),
 FOREIGN KEY(raw_text_object_id,book_version_id,book_id) REFERENCES stored_objects(id,book_version_id,book_id)
);
CREATE TABLE IF NOT EXISTS toc_entries (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),book_version_id uuid NOT NULL,book_id uuid NOT NULL,parent_id uuid,
 ordinal integer NOT NULL CHECK(ordinal>=0),label varchar(500) NOT NULL CHECK(length(btrim(label))>0),
 spine_item_id uuid,fragment varchar(1000),is_chapter boolean NOT NULL DEFAULT true,
 UNIQUE(book_version_id,ordinal),UNIQUE(id,book_version_id),
 FOREIGN KEY(book_version_id,book_id) REFERENCES book_versions(id,book_id),
 FOREIGN KEY(parent_id,book_version_id) REFERENCES toc_entries(id,book_version_id),
 FOREIGN KEY(spine_item_id,book_version_id,book_id) REFERENCES spine_items(id,book_version_id,book_id),
 CHECK(parent_id IS NULL OR parent_id<>id),CHECK(spine_item_id IS NOT NULL OR (fragment IS NULL AND NOT is_chapter))
);
CREATE INDEX IF NOT EXISTS toc_entries_parent_idx ON toc_entries(parent_id);
CREATE FUNCTION guard_reader_child_v1() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE version_id uuid; target_book uuid; version_state text;
BEGIN
 IF TG_OP='DELETE' THEN version_id=OLD.book_version_id; target_book=OLD.book_id;
 ELSE version_id=NEW.book_version_id; target_book=NEW.book_id; END IF;
 IF TG_OP='UPDATE' AND (NEW.id,NEW.book_version_id,NEW.book_id) IS DISTINCT FROM (OLD.id,OLD.book_version_id,OLD.book_id) THEN
  RAISE EXCEPTION 'Reader identity is immutable' USING ERRCODE='23514';
 END IF;
 SELECT state INTO version_state FROM book_versions WHERE id=version_id AND book_id=target_book FOR SHARE;
 IF version_state='READY' AND NOT (TG_OP='DELETE' AND EXISTS(SELECT 1 FROM books WHERE id=target_book AND status='DELETED')) THEN
  RAISE EXCEPTION 'READY reader structure is immutable' USING ERRCODE='23514';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER runtime_spine_immutable BEFORE INSERT OR UPDATE OR DELETE ON spine_items FOR EACH ROW EXECUTE FUNCTION guard_reader_child_v1();
CREATE TRIGGER runtime_toc_immutable BEFORE INSERT OR UPDATE OR DELETE ON toc_entries FOR EACH ROW EXECUTE FUNCTION guard_reader_child_v1();
CREATE FUNCTION guard_runtime_toc_parent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- Parent precedes child: this also rules out cycles and bounds traversal.
 IF NEW.parent_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM toc_entries WHERE id=NEW.parent_id AND book_version_id=NEW.book_version_id AND ordinal<NEW.ordinal) THEN
  RAISE EXCEPTION 'TOC parent must precede child in this version' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER runtime_toc_parent BEFORE INSERT OR UPDATE ON toc_entries FOR EACH ROW EXECUTE FUNCTION guard_runtime_toc_parent();
