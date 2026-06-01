package com.example.epub.core.repository.epub;
import com.example.epub.core.model.epub.EpubModels.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

public class IngestRepository {
    private final JdbcTemplate jdbc;
    public IngestRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public Optional<Source> source(UUID book,UUID version) {
        return jdbc.query("""
            SELECT o.* FROM book_versions v JOIN books b ON b.id=v.book_id
            JOIN stored_objects o ON o.id=v.source_object_id AND o.book_version_id=v.id AND o.book_id=v.book_id
            WHERE v.id=? AND b.id=? AND v.state='PROCESSING' AND b.status<>'DELETED' AND o.kind='SOURCE_EPUB' AND o.state='READY'
            """,(r,n)->new Source(book,version,new ObjectRow(r.getObject("id",UUID.class),r.getString("object_key"),r.getString("sha256"),r.getLong("size_bytes"),r.getString("media_type"),r.getString("kind"))),version,book).stream().findFirst();
    }
    public boolean lockProcessing(UUID book,UUID version) {
        if(jdbc.queryForList("SELECT id FROM books WHERE id=? AND status<>'DELETED' FOR UPDATE",book).isEmpty())return false;
        return !jdbc.queryForList("SELECT id FROM book_versions WHERE id=? AND book_id=? AND state='PROCESSING' FOR UPDATE",version,book).isEmpty();
    }
    public void object(UUID book,UUID version,ObjectRow row) {
        jdbc.update("INSERT INTO stored_objects(id,book_id,book_version_id,kind,object_key,sha256,size_bytes,media_type,state) VALUES (?,?,?,?,?,?,?,?,'READY')",row.id(),book,version,row.kind(),row.key(),row.hash(),row.size(),row.media());
    }
    public void resource(UUID book,UUID version,String href,UUID object) {jdbc.update("INSERT INTO reader_resources(book_version_id,book_id,href,object_id) VALUES (?,?,?,?)",version,book,href,object);}
    public void spine(UUID book,UUID version,UUID id,int index,String href,String title,boolean linear,UUID raw,int length) {
        jdbc.update("INSERT INTO spine_items(id,book_version_id,book_id,spine_index,href,title,is_linear,raw_text_object_id,raw_utf16_length) VALUES (?,?,?,?,?,?,?,?,?)",id,version,book,index,href,title,linear,raw,length);
    }
    public void toc(UUID book,UUID version,UUID id,UUID parent,int ordinal,String label,UUID spine,String fragment,boolean chapter) {
        jdbc.update("INSERT INTO toc_entries(id,book_version_id,book_id,parent_id,ordinal,label,spine_item_id,fragment,is_chapter) VALUES (?,?,?,?,?,?,?,?,?)",id,version,book,parent,ordinal,label,spine,fragment,chapter);
    }
    public boolean quota(UUID version,long actualBytes) {
        var found=jdbc.queryForList("SELECT id,bucket_id,amount_units FROM quota_reservations WHERE book_version_id=? AND state='RESERVED' FOR UPDATE",version);if(found.isEmpty())return false;
        var row=found.getFirst();long reserved=((Number)row.get("amount_units")).longValue();
        if(jdbc.update("UPDATE quota_buckets SET reserved_units=reserved_units-?,used_units=used_units+?,revision=revision+1 WHERE id=? AND reserved_units>=? AND used_units+reserved_units-?<=limit_units-?",reserved,actualBytes,row.get("bucket_id"),reserved,reserved,actualBytes)!=1)return false;
        jdbc.update("UPDATE quota_reservations SET state='COMMITTED',amount_units=? WHERE id=?",actualBytes,row.get("id"));return true;
    }
    public void ready(UUID book,UUID version,UUID reader,UUID cover,String extractor,String sanitizer,int chapterCount) {
        jdbc.update("UPDATE book_versions SET state='READY',reader_object_id=?,cover_object_id=?,extractor_version=?,sanitizer_version=?,ready_at=clock_timestamp(),failure_code=NULL,chapter_count=? WHERE id=? AND state='PROCESSING'",reader,cover,extractor,sanitizer,chapterCount,version);
        jdbc.update("""
            UPDATE books b SET current_version_id=?,revision=revision+1,updated_at=clock_timestamp() WHERE b.id=?
              AND (b.current_version_id IS NULL OR (SELECT version_no FROM book_versions WHERE id=b.current_version_id)<(SELECT version_no FROM book_versions WHERE id=?))
            """,version,book,version);
    }
    public List<UUID> terminalVersions() {
        return jdbc.queryForList("SELECT v.id FROM book_versions v JOIN jobs j ON j.book_version_id=v.id AND j.type='INGEST' WHERE v.state='PROCESSING' AND j.status IN ('FAILED','CANCELLED') ORDER BY v.created_at LIMIT 100",UUID.class);
    }
    public List<String> failTerminal(UUID version) {
        if(jdbc.queryForList("SELECT v.id FROM book_versions v JOIN jobs j ON j.book_version_id=v.id AND j.type='INGEST' WHERE v.id=? AND v.state='PROCESSING' AND j.status IN ('FAILED','CANCELLED') FOR UPDATE OF v SKIP LOCKED",version).isEmpty())return List.of();
        var keys=jdbc.queryForList("SELECT object_key FROM stored_objects WHERE book_version_id=?",String.class,version);
        jdbc.update("UPDATE book_versions SET state='FAILED',failure_code=coalesce((SELECT last_error_code FROM jobs WHERE book_version_id=? AND type='INGEST' AND status IN ('FAILED','CANCELLED') LIMIT 1),'INGEST_FAILED'),source_object_id=NULL WHERE id=?",version,version);
        var reservations=jdbc.queryForList("SELECT id,bucket_id,amount_units FROM quota_reservations WHERE book_version_id=? AND state='RESERVED' FOR UPDATE",version);
        for(var reservation:reservations) {jdbc.update("UPDATE quota_buckets SET reserved_units=reserved_units-?,revision=revision+1 WHERE id=?",reservation.get("amount_units"),reservation.get("bucket_id"));jdbc.update("UPDATE quota_reservations SET state='RELEASED' WHERE id=?",reservation.get("id"));}
        jdbc.update("DELETE FROM stored_objects WHERE book_version_id=?",version);return keys;
    }
    public Set<String> referenced(List<String> keys) {
        if(keys.isEmpty())return Set.of();return new HashSet<>(jdbc.queryForList("SELECT object_key FROM stored_objects WHERE backend='LOCAL' AND object_key IN ("+String.join(",",Collections.nCopies(keys.size(),"?"))+")",String.class,keys.toArray()));
    }
    public boolean acceptedSource(UUID book,String hash,String extractor,String sanitizer) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM book_versions v JOIN stored_objects o ON o.id=v.source_object_id WHERE v.book_id=? AND o.sha256=? AND (v.state='PROCESSING' OR (v.state='READY' AND v.extractor_version=? AND v.sanitizer_version=?)))",Boolean.class,book,hash,extractor,sanitizer));
    }
}
