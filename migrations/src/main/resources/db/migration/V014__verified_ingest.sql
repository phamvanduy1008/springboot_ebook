CREATE UNIQUE INDEX ingest_version_job_unique ON jobs(book_version_id) WHERE type='INGEST' AND book_version_id IS NOT NULL;
CREATE TABLE reader_resources (
 book_version_id uuid NOT NULL,book_id uuid NOT NULL,href varchar(200) NOT NULL CHECK(href ~ '^EPUB/r[0-9]+\.(xhtml|css|jpg|png|gif|ttf|otf|woff|woff2)$'),object_id uuid NOT NULL,
 PRIMARY KEY(book_version_id,href),UNIQUE(object_id),
 FOREIGN KEY(book_version_id,book_id) REFERENCES book_versions(id,book_id),
 FOREIGN KEY(object_id,book_version_id,book_id) REFERENCES stored_objects(id,book_version_id,book_id)
);
-- This table uses object IDs as identity, so its READY guard is separate from spine/TOC.
CREATE FUNCTION guard_reader_resource_v1() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE version_id uuid; target_book uuid; ready boolean;
BEGIN
 IF TG_OP='DELETE' THEN version_id=OLD.book_version_id;target_book=OLD.book_id;
 ELSE version_id=NEW.book_version_id;target_book=NEW.book_id;END IF;
 SELECT state='READY' INTO ready FROM book_versions WHERE id=version_id AND book_id=target_book FOR SHARE;
 IF ready AND NOT(TG_OP='DELETE' AND EXISTS(SELECT 1 FROM books WHERE id=target_book AND status='DELETED')) THEN
  RAISE EXCEPTION 'READY resource mapping is immutable' USING ERRCODE='23514';
 END IF;
 IF TG_OP='UPDATE' AND NEW IS DISTINCT FROM OLD THEN RAISE EXCEPTION 'Resource mapping is immutable' USING ERRCODE='23514';END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;END IF;RETURN NEW;
END $$;
CREATE TRIGGER runtime_resource_immutable BEFORE INSERT OR UPDATE OR DELETE ON reader_resources FOR EACH ROW EXECUTE FUNCTION guard_reader_resource_v1();
CREATE FUNCTION require_ready_structure_v1() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.state='READY' AND OLD.state<>'READY' THEN
  IF OLD.state<>'PROCESSING' OR NOT EXISTS(SELECT 1 FROM spine_items WHERE book_version_id=NEW.id)
   OR NOT EXISTS(SELECT 1 FROM toc_entries WHERE book_version_id=NEW.id)
   OR EXISTS(SELECT 1 FROM spine_items s JOIN stored_objects o ON o.id=s.raw_text_object_id WHERE s.book_version_id=NEW.id AND (o.kind<>'RAW_TEXT' OR o.state<>'READY'))
   OR EXISTS(SELECT 1 FROM spine_items s WHERE s.book_version_id=NEW.id AND NOT EXISTS(SELECT 1 FROM reader_resources rr JOIN stored_objects o ON o.id=rr.object_id WHERE rr.book_version_id=NEW.id AND rr.href=s.href AND o.kind='RESOURCE' AND o.state='READY')) THEN
   RAISE EXCEPTION 'READY requires processed spine, TOC, text and reader resources' USING ERRCODE='23514';
  END IF;
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER runtime_ready_structure BEFORE UPDATE ON book_versions FOR EACH ROW EXECUTE FUNCTION require_ready_structure_v1();
CREATE FUNCTION forbid_ready_object_insert_v1() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM book_versions WHERE id=NEW.book_version_id AND state='READY' FOR SHARE) THEN
  RAISE EXCEPTION 'Cannot append artifacts to READY versions' USING ERRCODE='23514';
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER runtime_ready_object_insert BEFORE INSERT ON stored_objects FOR EACH ROW EXECUTE FUNCTION forbid_ready_object_insert_v1();
