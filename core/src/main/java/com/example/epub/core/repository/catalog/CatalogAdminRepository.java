package com.example.epub.core.repository.catalog;
import com.example.epub.core.model.catalog.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
public class CatalogAdminRepository {
    private final JdbcTemplate jdbc;
    public CatalogAdminRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public boolean lockAdministrator(UUID id) {return !jdbc.queryForList("SELECT id FROM users WHERE id=? AND role='ADMIN' AND status='ACTIVE' FOR SHARE",UUID.class,id).isEmpty();}
    public boolean lockBook(UUID id) {return !jdbc.queryForList("SELECT id FROM books WHERE id=? AND visibility='PUBLIC' AND status<>'DELETED' FOR UPDATE",UUID.class,id).isEmpty();}
    public boolean lockCategory(UUID id) {return !jdbc.queryForList("SELECT id FROM categories WHERE id=? FOR UPDATE",UUID.class,id).isEmpty();}
    public boolean lockAuthor(UUID id) {return !jdbc.queryForList("SELECT id FROM authors WHERE id=? FOR NO KEY UPDATE",UUID.class,id).isEmpty();}
    public void createBook(UUID id,UUID actor,BookDraft value) {
        jdbc.update("INSERT INTO books(id,created_by,visibility,title,author_display,slug,summary,description,language,is_translation,publication_year,serial_status) VALUES (?,?,'PUBLIC',?,?,?,?,?,?,?,?,?)",
            id,actor,value.title(),value.authorDisplay(),value.slug(),value.summary(),value.description(),value.language(),value.isTranslation(),value.publicationYear(),value.serialStatus());
    }
    public boolean updateBook(UUID id,long revision,BookDraft value) {
        return jdbc.update("UPDATE books SET title=?,author_display=?,slug=?,summary=?,description=?,language=?,is_translation=?,publication_year=?,serial_status=?,updated_at=clock_timestamp(),revision=revision+1 WHERE id=? AND revision=?",
            value.title(),value.authorDisplay(),value.slug(),value.summary(),value.description(),value.language(),value.isTranslation(),value.publicationYear(),value.serialStatus(),id,revision)==1;
    }
    public void relations(UUID id,BookDraft value) {
        jdbc.update("DELETE FROM book_categories WHERE book_id=?",id);jdbc.update("DELETE FROM book_authors WHERE book_id=?",id);jdbc.update("DELETE FROM book_tags WHERE book_id=?",id);
        for(UUID category:value.categoryIds())jdbc.update("INSERT INTO book_categories(book_id,category_id) VALUES (?,?)",id,category);
        for(int i=0;i<value.authorIds().size();i++)jdbc.update("INSERT INTO book_authors(book_id,author_id,position) VALUES (?,?,?)",id,value.authorIds().get(i),i);
        for(String tag:value.tags())jdbc.update("INSERT INTO book_tags(book_id,tag) VALUES (?,?)",id,tag);
    }
    public BookVersion newVersion(UUID book,String rights) {
        UUID id=UUID.randomUUID();int number=jdbc.queryForObject("SELECT coalesce(max(version_no),0)+1 FROM book_versions WHERE book_id=?",Integer.class,book);
        jdbc.update("INSERT INTO book_versions(id,book_id,version_no,rights_note) VALUES (?,?,?,?)",id,book,number,rights);
        return jdbc.queryForObject("SELECT created_at FROM book_versions WHERE id=?",(row,i) -> new BookVersion(id,number,"UPLOADING",rights,row.getTimestamp(1).toInstant()),id);
    }
    public boolean touchBook(UUID id,long revision) {return jdbc.update("UPDATE books SET revision=revision+1,updated_at=clock_timestamp() WHERE id=? AND revision=?",id,revision)==1;}
    public void draftRights(UUID id,String rights) {jdbc.update("UPDATE book_versions SET rights_note=? WHERE book_id=? AND version_no=(SELECT max(version_no) FROM book_versions WHERE book_id=?) AND state='UPLOADING'",rights,id,id);}
    public boolean hide(UUID id,long revision) {return jdbc.update("UPDATE books SET status='HIDDEN',revision=revision+1,updated_at=clock_timestamp() WHERE id=? AND revision=? AND status='PUBLISHED'",id,revision)==1;}
    public boolean deleteBook(UUID id,long revision) {return jdbc.update("UPDATE books SET status='DELETED',deleted_at=clock_timestamp(),updated_at=clock_timestamp(),revision=revision+1 WHERE id=? AND revision=?",id,revision)==1;}
    public void createCategory(UUID id,String name,String slug,String group,String description,String color,int order) {jdbc.update("INSERT INTO categories(id,name,slug,group_name,description,color,sort_order) VALUES (?,?,?,?,?,?,?)",id,name,slug,group,description,color,order);}
    public boolean updateCategory(UUID id,long revision,String name,String slug,String group,String description,String color,int order) {return jdbc.update("UPDATE categories SET name=?,slug=?,group_name=?,description=?,color=?,sort_order=?,revision=revision+1 WHERE id=? AND revision=?",name,slug,group,description,color,order,id,revision)==1;}
    public long categoryReferences(UUID id) {return jdbc.queryForObject("SELECT count(*) FROM book_categories WHERE category_id=?",Long.class,id);}
    public boolean deleteCategory(UUID id,long revision) {return jdbc.update("DELETE FROM categories WHERE id=? AND revision=?",id,revision)==1;}
    public void createAuthor(UUID id,String name,String slug,String bio) {jdbc.update("INSERT INTO authors(id,name,slug,biography) VALUES (?,?,?,?)",id,name,slug,bio);}
    public boolean updateAuthor(UUID id,long revision,String name,String slug,String bio) {return jdbc.update("UPDATE authors SET name=?,slug=?,biography=?,revision=revision+1 WHERE id=? AND revision=?",name,slug,bio,id,revision)==1;}
    public void refreshAuthorDisplay(UUID author) {
        var books=jdbc.queryForList("SELECT b.id FROM books b JOIN book_authors ba ON ba.book_id=b.id WHERE ba.author_id=? ORDER BY b.id FOR UPDATE OF b",UUID.class,author);
        for(UUID book:books)jdbc.update("UPDATE books b SET author_display=(SELECT string_agg(a.name,', ' ORDER BY ba.position) FROM book_authors ba JOIN authors a ON a.id=ba.author_id WHERE ba.book_id=b.id),revision=revision+1,updated_at=clock_timestamp() WHERE b.id=?",book);
    }
    public boolean deleteAuthor(UUID id,long revision) {return jdbc.update("DELETE FROM authors WHERE id=? AND revision=?",id,revision)==1;}
    public void audit(String action,UUID actor,String targetType,UUID target,String requestId) {
        jdbc.update("INSERT INTO audit_logs(actor_id,action,target_type,target_id,request_id) VALUES (?,?,?,?,?)",actor,action,targetType,target,requestId);
    }
}
