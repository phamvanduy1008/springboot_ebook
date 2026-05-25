package com.example.epub.core.repository.epub;
import com.example.epub.core.model.epub.EpubModels.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

public class UploadRepository {
    private final JdbcTemplate jdbc;
    public UploadRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public boolean lockUser(UUID user) {return !jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",user).isEmpty();}
    public Optional<Book> book(UUID id,boolean lock) {
        return jdbc.query("SELECT id,owner_id,visibility,status,revision FROM books WHERE id=?"+(lock?" FOR UPDATE":""),(r,n)->new Book(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getLong(5)),id).stream().findFirst();
    }
    public Optional<Receipt> receipt(UUID user,UUID key) {
        return jdbc.query("SELECT * FROM epub_upload_receipts WHERE user_id=? AND mutation_key=?",(r,n)->new Receipt(r.getString("fingerprint"),new Accepted(r.getObject("book_id",UUID.class),r.getObject("version_id",UUID.class),r.getObject("job_id",UUID.class),r.getLong("book_revision"),"PROCESSING")),user,key).stream().findFirst();
    }
    public UUID privateBook(UUID user,String title,String author) {
        UUID book=UUID.randomUUID();jdbc.update("INSERT INTO books(id,owner_id,created_by,visibility,title,author_display,slug) VALUES (?,?,?,'PRIVATE',?,?,'private-import')",book,user,user,title,author);return book;
    }
    public boolean reserve(UUID user,UUID key,UUID version,long bytes,long limit) {
        jdbc.update("INSERT INTO quota_buckets(user_id,kind,window_start,window_end,limit_units) VALUES (?,'STORAGE_BYTES',TIMESTAMPTZ '2000-01-01Z',TIMESTAMPTZ '2100-01-01Z',?) ON CONFLICT(user_id,kind,window_start) DO NOTHING",user,limit);
        UUID bucket=jdbc.queryForObject("SELECT id FROM quota_buckets WHERE user_id=? AND kind='STORAGE_BYTES' AND window_start=TIMESTAMPTZ '2000-01-01Z'",UUID.class,user);
        if(jdbc.update("UPDATE quota_buckets SET reserved_units=reserved_units+?,revision=revision+1 WHERE id=? AND used_units+reserved_units<=limit_units-?",bytes,bucket,bytes)!=1)return false;
        jdbc.update("INSERT INTO quota_reservations(user_id,bucket_id,request_id,amount_units,expires_at,book_version_id) VALUES (?,?,?,?,clock_timestamp()+interval '48 hours',?)",user,bucket,key,bytes,version);return true;
    }
    public int processing(UUID owner) {return jdbc.queryForObject("SELECT count(*) FROM book_versions v JOIN books b ON b.id=v.book_id WHERE b.owner_id=? AND v.state='PROCESSING'",Integer.class,owner);}
    public UUID version(UUID book,String rights) {
        UUID version=UUID.randomUUID();jdbc.update("INSERT INTO book_versions(id,book_id,version_no,state,rights_note) SELECT ?,?,coalesce(max(version_no),0)+1,'PROCESSING',? FROM book_versions WHERE book_id=?",version,book,rights,book);return version;
    }
    public void source(UUID book,UUID version,UUID object,String key,String hash,long size) {
        jdbc.update("INSERT INTO stored_objects(id,book_id,book_version_id,kind,object_key,sha256,size_bytes,media_type,state) VALUES (?,?,?,'SOURCE_EPUB',?,?,?,'application/epub+zip','READY')",object,book,version,key,hash,size);
        jdbc.update("UPDATE book_versions SET source_object_id=? WHERE id=?",object,version);
    }
    public long touch(UUID book) {return jdbc.queryForObject("UPDATE books SET revision=revision+1,updated_at=clock_timestamp() WHERE id=? RETURNING revision",Long.class,book);}
    public void receipt(UUID user,UUID key,String fingerprint,Accepted result) {
        jdbc.update("INSERT INTO epub_upload_receipts(user_id,mutation_key,fingerprint,book_id,version_id,job_id,book_revision) VALUES (?,?,?,?,?,?,?)",user,key,fingerprint,result.bookId(),result.versionId(),result.jobId(),result.revision());
    }
}
