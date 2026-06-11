package com.example.epub.core.repository.catalog;
import com.example.epub.core.model.catalog.*;
import com.example.epub.core.model.catalog.EditorialModels.*;
import com.example.epub.core.model.catalog.EditorialModels.Character;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
public class EditorialRepository {
 private final JdbcTemplate jdbc;public EditorialRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
 public String quote(UUID id) {return jdbc.queryForObject("SELECT quote FROM books WHERE id=?",String.class,id);}
 public Integer chapterCount(UUID id) {return jdbc.queryForObject("SELECT v.chapter_count FROM books b LEFT JOIN book_versions v ON v.id=b.published_version_id AND v.book_id=b.id AND v.state='READY' WHERE b.id=? AND "+CatalogRepository.PUBLIC,Integer.class,id);}
 public List<Character> characters(UUID id) {return jdbc.query("SELECT * FROM book_characters WHERE book_id=? ORDER BY position,id",(rs,n)->new Character(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("role"),rs.getString("description"),rs.getString("initials"),rs.getString("color"),rs.getInt("position"),rs.getLong("revision")),id);}
 public void quote(UUID id,String quote) {jdbc.update("UPDATE books SET quote=? WHERE id=?",quote,id);}
 public void characters(UUID id,List<Character> values) {
  jdbc.update("DELETE FROM book_characters WHERE book_id=?",id);
  for(var c:values)jdbc.update("INSERT INTO book_characters(id,book_id,name,role,description,initials,color,position,revision) VALUES (?,?,?,?,?,?,?,?,?)",c.id(),id,c.name(),c.role(),c.description(),c.initials(),c.color(),c.position(),c.revision());
 }
 public List<UUID> related(UUID id) {return jdbc.queryForList("SELECT b.id FROM books b WHERE "+CatalogRepository.PUBLIC+" AND b.id<>? AND EXISTS(SELECT 1 FROM book_categories bc JOIN book_categories source ON source.category_id=bc.category_id WHERE source.book_id=? AND bc.book_id=b.id) ORDER BY b.updated_at DESC,b.id LIMIT 6",UUID.class,id,id);}
}
