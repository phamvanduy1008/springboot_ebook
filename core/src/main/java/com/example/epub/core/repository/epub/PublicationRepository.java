package com.example.epub.core.repository.epub;
import com.example.epub.core.model.epub.PublicationModels.*;
import com.example.epub.core.model.epub.EpubModels.ObjectRow;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
public class PublicationRepository {
    private final JdbcTemplate jdbc;
    public PublicationRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public boolean lockAdmin(UUID actor) {return !jdbc.queryForList("SELECT id FROM users WHERE id=? AND role='ADMIN' AND status='ACTIVE' FOR UPDATE",actor).isEmpty();}
    public Optional<State> lockBook(UUID book) {
        return jdbc.query("SELECT id,published_version_id,status,revision FROM books WHERE id=? AND visibility='PUBLIC' AND status<>'DELETED' FOR UPDATE",(r,n)->new State(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getLong(4)),book).stream().findFirst();
    }
    public Optional<Receipt> receipt(UUID actor,UUID key) {
        return jdbc.query("SELECT * FROM epub_publication_receipts WHERE user_id=? AND mutation_key=?",(r,n)->new Receipt(r.getObject("book_id",UUID.class),r.getObject("version_id",UUID.class),r.getLong("base_revision"),r.getString("action"),new State(r.getObject("book_id",UUID.class),r.getObject("result_version_id",UUID.class),r.getString("result_status"),r.getLong("result_revision"))),actor,key).stream().findFirst();
    }
    public boolean ready(UUID book,UUID version) {
        return !jdbc.queryForList("SELECT id FROM book_versions WHERE id=? AND book_id=? AND state='READY' FOR SHARE",version,book).isEmpty()
            && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM stored_objects WHERE book_version_id=? AND (state<>'READY' OR backend<>'LOCAL'))",Boolean.class,version));
    }
    public List<ObjectRow> objects(UUID version) {
        return jdbc.query("SELECT * FROM stored_objects WHERE book_version_id=? ORDER BY id",(r,n)->new ObjectRow(r.getObject("id",UUID.class),r.getString("object_key"),r.getString("sha256"),r.getLong("size_bytes"),r.getString("media_type"),r.getString("kind")),version);
    }
    public State publish(UUID book,UUID version,long revision) {
        jdbc.update("""
            UPDATE books SET published_version_id=?,current_version_id=coalesce(current_version_id,?),status='PUBLISHED',
              cover_key=(SELECT o.object_key FROM book_versions v LEFT JOIN stored_objects o ON o.id=v.cover_object_id WHERE v.id=?),
              revision=revision+1,updated_at=clock_timestamp() WHERE id=? AND revision=?
            """,version,version,version,book,revision);return new State(book,version,"PUBLISHED",revision+1);
    }
    public State unpublish(UUID book,long revision) {
        jdbc.update("UPDATE books SET published_version_id=NULL,status='HIDDEN',revision=revision+1,updated_at=clock_timestamp() WHERE id=? AND revision=?",book,revision);return new State(book,null,"HIDDEN",revision+1);
    }
    public void record(UUID actor,UUID key,UUID book,UUID version,long base,String action,State result,String requestId) {
        jdbc.update("INSERT INTO epub_publication_receipts(user_id,mutation_key,book_id,version_id,base_revision,action,result_revision,result_status,result_version_id) VALUES (?,?,?,?,?,?,?,?,?)",actor,key,book,version,base,action,result.revision(),result.status(),result.publishedVersionId());
        jdbc.update("INSERT INTO audit_logs(actor_id,action,target_type,target_id,request_id) VALUES (?,?,'BOOK',?,?)",actor,action.equals("PUBLISH")?"BOOK_PUBLISHED":"BOOK_UNPUBLISHED",book,requestId);
    }
}
