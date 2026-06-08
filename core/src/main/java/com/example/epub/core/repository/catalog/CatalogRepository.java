package com.example.epub.core.repository.catalog;
import com.example.epub.core.model.catalog.*;
import java.sql.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
public class CatalogRepository {
    protected final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    public CatalogRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;this.named=new NamedParameterJdbcTemplate(jdbc);}
    public static final String PUBLIC="b.visibility='PUBLIC' AND b.status='PUBLISHED' AND EXISTS(SELECT 1 FROM book_versions pv WHERE pv.id=b.published_version_id AND pv.book_id=b.id AND pv.state='READY')";
    private static final String COLUMNS="b.*,coalesce((SELECT max(v.version_no) FROM book_versions v WHERE v.book_id=b.id),0) AS version_no,(SELECT v.state FROM book_versions v WHERE v.book_id=b.id ORDER BY v.version_no DESC LIMIT 1) AS version_state";
    protected CatalogBook book(ResultSet row,int index) throws SQLException {
        return new CatalogBook(row.getObject("id",UUID.class),row.getString("title"),row.getString("author_display"),row.getString("slug"),row.getString("summary"),row.getString("description"),
            row.getString("language"),row.getBoolean("is_translation"),row.getObject("publication_year",Integer.class),row.getString("serial_status"),row.getString("status"),row.getString("visibility"),row.getLong("revision"),
            row.getTimestamp("created_at").toInstant(),row.getTimestamp("updated_at").toInstant(),row.getObject("published_version_id",UUID.class),row.getObject("current_version_id",UUID.class),row.getString("cover_key"),row.getInt("version_no"),row.getString("version_state"));
    }
    private String where(CatalogQuery query,boolean admin,List<Object> args) {
        String where=admin?"b.visibility='PUBLIC' AND b.status<>'DELETED'":PUBLIC;
        if(query.q()!=null && !query.q().isBlank()) {where+=" AND position(catalog_fold_v1(?) in b.search_text)>0";args.add(query.q());}
        if(query.category()!=null) {where+=" AND EXISTS(SELECT 1 FROM book_categories bc WHERE bc.book_id=b.id AND bc.category_id=?)";args.add(query.category());}
        if(query.year()!=null) {where+=" AND b.publication_year=?";args.add(query.year());}
        if(admin && query.status()!=null) {where+=" AND b.status=?";args.add(query.status());}
        return where;
    }
    private String order(String value) {
        return switch(value) {case "title,asc" -> "b.title ASC,b.id ASC";case "title,desc" -> "b.title DESC,b.id DESC";case "author,asc" -> "b.author_display ASC,b.id ASC";case "createdAt,desc" -> "b.created_at DESC,b.id DESC";case "updatedAt,desc" -> "b.updated_at DESC,b.id DESC";default -> throw new IllegalArgumentException("Unsupported sort");};
    }
    public CatalogPage<CatalogBook> books(CatalogQuery query,boolean admin) {
        var args=new ArrayList<Object>();String where=where(query,admin,args);
        long total=jdbc.queryForObject("SELECT count(*) FROM books b WHERE "+where,Long.class,args.toArray());
        args.add(query.size());args.add((long)query.page()*query.size());
        var rows=jdbc.query("SELECT "+COLUMNS+" FROM books b WHERE "+where+" ORDER BY "+order(query.sort())+" LIMIT ? OFFSET ?",this::book,args.toArray());
        return new CatalogPage<>(rows,query.page(),query.size(),total);
    }
    public Optional<CatalogBook> find(UUID id,boolean admin) {
        return jdbc.query("SELECT "+COLUMNS+" FROM books b WHERE b.id=? AND "+(admin?"b.visibility='PUBLIC' AND b.status<>'DELETED'":PUBLIC),this::book,id).stream().findFirst();
    }
    private Category mapCategory(ResultSet row,int index) throws SQLException {
        return new Category(row.getObject("id",UUID.class),row.getString("name"),row.getString("slug"),row.getString("group_name"),row.getString("description"),row.getString("color"),row.getInt("sort_order"),row.getLong("revision"),row.getLong("book_count"));
    }
    private String categoryColumns(boolean admin) {
        return "c.*,(SELECT count(*) FROM book_categories bc JOIN books b ON b.id=bc.book_id WHERE bc.category_id=c.id AND "+PUBLIC+") AS book_count";
    }
    public CatalogPage<Category> categories(String q,String group,String sort,int page,int size,boolean admin) {
        String where="true";var args=new ArrayList<Object>();
        if(q!=null && !q.isBlank()) {where+=" AND position(catalog_fold_v1(?) in catalog_fold_v1(c.name||' '||c.description))>0";args.add(q);}
        if(group!=null && !group.isBlank()) {where+=" AND c.group_name=?";args.add(group);}
        long total=jdbc.queryForObject("SELECT count(*) FROM categories c WHERE "+where,Long.class,args.toArray());
        String order=switch(sort) {case "name,asc" -> "c.name,c.id";case "bookCount,desc" -> "book_count DESC,c.id";case "sortOrder,asc" -> "c.sort_order,c.id";default -> throw new IllegalArgumentException("Unsupported sort");};
        args.add(size);args.add((long)page*size);
        return new CatalogPage<>(jdbc.query("SELECT "+categoryColumns(admin)+" FROM categories c WHERE "+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",this::mapCategory,args.toArray()),page,size,total);
    }
    public Optional<Category> category(UUID id,boolean admin) {return jdbc.query("SELECT "+categoryColumns(admin)+" FROM categories c WHERE c.id=?",this::mapCategory,id).stream().findFirst();}
    private Author mapAuthor(ResultSet row,int index) throws SQLException {return new Author(row.getObject("id",UUID.class),row.getString("name"),row.getString("slug"),row.getString("biography"),row.getLong("revision"),row.getLong("book_count"));}
    public CatalogPage<Author> authors(int page,int size) {
        return new CatalogPage<>(jdbc.query("SELECT "+authorColumns()+" FROM authors a ORDER BY a.name,a.id LIMIT ? OFFSET ?",this::mapAuthor,size,(long)page*size),page,size,jdbc.queryForObject("SELECT count(*) FROM authors",Long.class));
    }
    private String authorColumns() {return "a.*,(SELECT count(*) FROM book_authors ba JOIN books b ON b.id=ba.book_id WHERE ba.author_id=a.id AND "+PUBLIC+") AS book_count";}
    public Optional<Author> author(UUID id) {return jdbc.query("SELECT "+authorColumns()+" FROM authors a WHERE a.id=?",this::mapAuthor,id).stream().findFirst();}
    public List<BookVersion> versions(UUID book) {return jdbc.query("SELECT id,version_no,state,rights_note,created_at FROM book_versions WHERE book_id=? ORDER BY version_no DESC",(row,i) -> new BookVersion(row.getObject("id",UUID.class),row.getInt("version_no"),row.getString("state"),row.getString("rights_note"),row.getTimestamp("created_at").toInstant()),book);}
    public List<BookView> views(List<CatalogBook> books,boolean includeVersions) {
        if(books.isEmpty())return List.of();
        var ids=books.stream().map(CatalogBook::id).toList();var parameters=Map.of("ids",ids);
        Map<UUID,List<Category>> categories=new HashMap<>();Map<UUID,List<Author>> authors=new HashMap<>();Map<UUID,List<String>> tags=new HashMap<>();
        named.query("SELECT bc.book_id,"+categoryColumns(false)+" FROM book_categories bc JOIN categories c ON c.id=bc.category_id WHERE bc.book_id IN (:ids) ORDER BY c.sort_order,c.id",parameters,
            (org.springframework.jdbc.core.RowCallbackHandler)row -> categories.computeIfAbsent(row.getObject("book_id",UUID.class),key -> new ArrayList<>()).add(mapCategory(row,0)));
        named.query("SELECT ba.book_id,"+authorColumns()+" FROM book_authors ba JOIN authors a ON a.id=ba.author_id WHERE ba.book_id IN (:ids) ORDER BY ba.position,a.id",parameters,
            (org.springframework.jdbc.core.RowCallbackHandler)row -> authors.computeIfAbsent(row.getObject("book_id",UUID.class),key -> new ArrayList<>()).add(mapAuthor(row,0)));
        named.query("SELECT book_id,tag FROM book_tags WHERE book_id IN (:ids) ORDER BY tag",parameters,
            (org.springframework.jdbc.core.RowCallbackHandler)row -> tags.computeIfAbsent(row.getObject("book_id",UUID.class),key -> new ArrayList<>()).add(row.getString("tag")));
        return books.stream().map(book -> new BookView(book,categories.getOrDefault(book.id(),List.of()),authors.getOrDefault(book.id(),List.of()),tags.getOrDefault(book.id(),List.of()),includeVersions?versions(book.id()):List.of())).toList();
    }

    public List<String> groups() {return jdbc.queryForList("SELECT DISTINCT group_name FROM categories ORDER BY group_name",String.class);}
    public List<Integer> years() {return jdbc.queryForList("SELECT DISTINCT b.publication_year FROM books b WHERE "+PUBLIC+" AND b.publication_year IS NOT NULL ORDER BY b.publication_year DESC",Integer.class);}
    public List<Author> publicAuthors(int limit) {
        return jdbc.query("SELECT a.*,count(DISTINCT b.id) AS book_count FROM authors a JOIN book_authors ba ON ba.author_id=a.id JOIN books b ON b.id=ba.book_id WHERE "+PUBLIC+" GROUP BY a.id ORDER BY book_count DESC,a.id LIMIT ?",this::mapAuthor,limit);
    }

 public Map<UUID,CatalogBook> publicByIds(List<UUID> ids) {
  if(ids.isEmpty())return Map.of();
  return named.query("SELECT "+COLUMNS+" FROM books b WHERE b.id IN (:ids) AND "+PUBLIC,Map.of("ids",ids),this::book).stream().collect(java.util.stream.Collectors.toMap(CatalogBook::id,java.util.function.Function.identity()));
 }
}
