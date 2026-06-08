package com.example.epub.core.repository.epub;
import com.example.epub.core.model.epub.ReaderModels.*;
import com.example.epub.core.model.epub.EpubModels.ObjectRow;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
public class ReaderRepository {
    private final JdbcTemplate jdbc;
    public ReaderRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public Optional<Version> version(UUID book,UUID requested,UUID actor) {
        return jdbc.query("""
            SELECT b.id,v.id,b.visibility,v.toc_source FROM books b JOIN book_versions v
             ON v.id=coalesce(?,CASE WHEN b.visibility='PUBLIC' THEN b.published_version_id ELSE b.current_version_id END) AND v.book_id=b.id
            WHERE b.id=? AND b.status<>'DELETED' AND b.deleted_at IS NULL AND v.state='READY'
             AND ((b.visibility='PUBLIC' AND b.status='PUBLISHED' AND b.published_version_id=v.id) OR (b.visibility='PRIVATE' AND b.owner_id=?))
            """,(r,n)->new Version(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3).equals("PRIVATE"),r.getString(4)),requested,book,actor).stream().findFirst();
    }
    private ObjectRow object(java.sql.ResultSet r,int n) throws java.sql.SQLException {return new ObjectRow(r.getObject("id",UUID.class),r.getString("object_key"),r.getString("sha256"),r.getLong("size_bytes"),r.getString("media_type"),r.getString("kind"));}
    public Optional<ObjectRow> reader(UUID version,boolean cover) {
        return jdbc.query("SELECT o.* FROM book_versions v JOIN stored_objects o ON o.id=v."+(cover?"cover_object_id":"reader_object_id")+" AND o.book_version_id=v.id WHERE v.id=? AND o.kind=? AND o.backend='LOCAL' AND o.state='READY'",this::object,version,cover?"COVER":"READER_EPUB").stream().findFirst();
    }
    public Optional<ObjectRow> resource(UUID version,String href) {
        return jdbc.query("SELECT o.* FROM reader_resources rr JOIN stored_objects o ON o.id=rr.object_id AND o.book_version_id=rr.book_version_id WHERE rr.book_version_id=? AND rr.href=? AND o.kind IN ('RESOURCE','COVER') AND o.backend='LOCAL' AND o.state='READY'",this::object,version,href).stream().findFirst();
    }
    public List<Asset> resources(Version version) {
        return jdbc.query("SELECT o.id,rr.href,o.media_type,o.size_bytes,o.sha256 FROM reader_resources rr JOIN stored_objects o ON o.id=rr.object_id WHERE rr.book_version_id=? AND o.kind IN ('RESOURCE','COVER') AND o.backend='LOCAL' AND o.state='READY' ORDER BY rr.href",(r,n)->new Asset(r.getObject(1,UUID.class),r.getString(2),base(version)+"/resources/"+r.getString(2).substring(5),r.getString(3),r.getLong(4),r.getString(5)),version.versionId());
    }
    public List<Spine> spine(Version version) {
        return jdbc.query("SELECT * FROM spine_items WHERE book_version_id=? ORDER BY spine_index",(r,n)->new Spine(r.getObject("id",UUID.class),r.getInt("spine_index"),r.getString("title"),r.getString("href"),base(version)+"/resources/"+r.getString("href").substring(5),r.getBoolean("is_linear"),r.getInt("raw_utf16_length")),version.versionId());
    }
    public List<Toc> toc(UUID version) {
        return jdbc.query("SELECT * FROM toc_entries WHERE book_version_id=? ORDER BY ordinal",(r,n)->new Toc(r.getObject("id",UUID.class),r.getObject("parent_id",UUID.class),r.getInt("ordinal"),r.getString("label"),r.getObject("spine_item_id",UUID.class),r.getString("fragment"),r.getBoolean("is_chapter")),version);
    }
    private static String base(Version version) {return "/api/v1/books/"+version.bookId()+"/versions/"+version.versionId();}
}
