package com.example.epub.core.repository.library;

import com.example.epub.core.model.catalog.CatalogBook;
import com.example.epub.core.model.library.LibraryModels.*;
import com.example.epub.core.repository.catalog.CatalogRepository;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

public class LibraryRepository extends CatalogRepository {
    // ADMIN has no special access to another user's private metadata.
    private static final String ACCESS = "b.deleted_at IS NULL AND ((b.visibility='PRIVATE' AND b.owner_id=?) OR ("+PUBLIC+"))";
    private static final String COLUMNS = "b.*,0 AS version_no,NULL::varchar AS version_state";
    public LibraryRepository(JdbcTemplate jdbc) { super(jdbc); }
    public boolean lockUser(UUID user) { return !jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",UUID.class,user).isEmpty(); }
    public Optional<CatalogBook> accessible(UUID user,UUID id) {
        return jdbc.query("SELECT "+COLUMNS+" FROM books b WHERE b.id=? AND "+ACCESS,this::book,id,user).stream().findFirst();
    }
    private State mapState(ResultSet row,int index) throws SQLException {
        Timestamp viewed=row.getTimestamp("viewed_at");
        return new State(row.getObject("book_id",UUID.class),row.getString("shelf"),row.getBoolean("favorite"),viewed==null?null:viewed.toInstant(),row.getLong("revision"),row.getBoolean("owned"));
    }
    public State state(UUID user,UUID book,boolean owned) {
        return jdbc.query("SELECT s.*,? AS owned FROM personal_book_state s WHERE user_id=? AND book_id=?",this::mapState,owned,user,book).stream().findFirst().orElse(new State(book,null,false,null,0,owned));
    }
    public boolean exists(UUID user,UUID book) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM personal_book_state WHERE user_id=? AND book_id=?)",Boolean.class,user,book)); }
    public void write(UUID user,State old,String shelf,boolean favorite,Instant viewed,Instant at) {
        jdbc.update("INSERT INTO personal_book_state(user_id,book_id) VALUES (?,?) ON CONFLICT DO NOTHING",user,old.bookId());
        jdbc.update("UPDATE personal_book_state SET saved_at=CASE WHEN ?::varchar IS NULL THEN NULL WHEN shelf IS NULL THEN ? ELSE saved_at END,shelf=?,liked_at=CASE WHEN NOT ? THEN NULL WHEN NOT favorite THEN ? ELSE liked_at END,favorite=?,viewed_at=?,revision=revision+1,updated_at=? WHERE user_id=? AND book_id=?",
            shelf,Timestamp.from(at),shelf,favorite,Timestamp.from(at),favorite,viewed==null?null:Timestamp.from(viewed),Timestamp.from(at),user,old.bookId());
    }
    public void trimHistory(UUID user) {
        jdbc.update("UPDATE personal_book_state SET viewed_at=NULL,revision=revision+1 WHERE user_id=? AND book_id IN (SELECT book_id FROM personal_book_state WHERE user_id=? AND viewed_at IS NOT NULL ORDER BY viewed_at DESC,book_id LIMIT ALL OFFSET 100)",user,user);
    }
    private String where(UUID user,String kind,Query query,List<Object> args) {
        args.add(user);args.add(user);
        String where="s.user_id=? AND "+ACCESS;
        where+=switch(kind){case "favorites" -> " AND s.favorite";case "history" -> " AND s.viewed_at IS NOT NULL";default -> " AND s.shelf IS NOT NULL";};
        if(query.shelf()!=null && !query.shelf().equals("all")) {where+=" AND s.shelf=?";args.add(query.shelf());}
        if("owned".equals(query.ownership()))where+=" AND b.visibility='PRIVATE'";
        if("public".equals(query.ownership()))where+=" AND b.visibility='PUBLIC'";
        if(query.q()!=null && !query.q().isBlank()){where+=" AND position(catalog_fold_v1(?) in b.search_text)>0";args.add(query.q());}
        return where;
    }
    public Page list(UUID user,String kind,Query query) {
        var args=new ArrayList<Object>();String where=where(user,kind,query,args);
        long total=jdbc.queryForObject("SELECT count(*) FROM personal_book_state s JOIN books b ON b.id=s.book_id WHERE "+where,Long.class,args.toArray());
        String order=switch(query.sort()){case "title" -> "b.title ASC,b.id ASC";case "author" -> "b.author_display ASC,b.id ASC";default -> (kind.equals("history")?"s.viewed_at":kind.equals("favorites")?"s.liked_at":"s.saved_at")+" DESC,b.id ASC";};
        args.add(query.size());args.add((long)query.page()*query.size());
        var books=jdbc.query("SELECT "+COLUMNS+" FROM personal_book_state s JOIN books b ON b.id=s.book_id WHERE "+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",this::book,args.toArray());
        var entries=views(books,false).stream().map(view->new Entry(view,state(user,view.book().id(),view.book().visibility().equals("PRIVATE")))).toList();
        return new Page(entries,query.page(),query.size(),total,counts(user));
    }
    public Counts counts(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FILTER(WHERE s.shelf IS NOT NULL) AS all_count,count(*) FILTER(WHERE s.shelf='want') AS want,count(*) FILTER(WHERE s.shelf='reading') AS reading,count(*) FILTER(WHERE s.shelf='finished') AS finished,count(*) FILTER(WHERE s.shelf IS NOT NULL AND b.visibility='PRIVATE') AS owned,count(*) FILTER(WHERE s.favorite) AS favorites,count(*) FILTER(WHERE s.viewed_at IS NOT NULL) AS history FROM personal_book_state s JOIN books b ON b.id=s.book_id WHERE s.user_id=? AND "+ACCESS,
            (r,n)->new Counts(r.getLong("all_count"),r.getLong("want"),r.getLong("reading"),r.getLong("finished"),r.getLong("owned"),r.getLong("favorites"),r.getLong("history")),user,user);
    }
    public void cleanReceipts(UUID user,Instant before) { jdbc.update("DELETE FROM personal_mutations WHERE user_id=? AND created_at<?",user,Timestamp.from(before)); }
    public Optional<String> fingerprint(UUID user,UUID key) {
        return jdbc.queryForList("SELECT fingerprint FROM personal_mutations WHERE user_id=? AND mutation_key=?",String.class,user,key).stream().findFirst();
    }
    public State replayState(UUID user,UUID key) {
        return jdbc.queryForObject("SELECT (result->>'bookId')::uuid AS book_id,result->>'shelf' AS shelf,(result->>'favorite')::boolean AS favorite,(result->>'viewedAt')::timestamptz AS viewed_at,(result->>'revision')::bigint AS revision,(result->>'owned')::boolean AS owned FROM personal_mutations WHERE user_id=? AND mutation_key=?",this::mapState,user,key);
    }
    public void receipt(UUID user,UUID key,String fingerprint,State state) {
        jdbc.update("INSERT INTO personal_mutations(user_id,mutation_key,fingerprint,result) VALUES (?,?,?,jsonb_build_object('bookId',?::uuid,'shelf',?::text,'favorite',?::boolean,'viewedAt',?::timestamptz,'revision',?::bigint,'owned',?::boolean))",user,key,fingerprint,state.bookId(),state.shelf(),state.favorite(),state.viewedAt()==null?null:Timestamp.from(state.viewedAt()),state.revision(),state.owned());
    }
    private Settings mapSettings(ResultSet row,int index) throws SQLException {return new Settings(row.getString("theme"),row.getString("reader_theme"),row.getInt("font_size"),row.getDouble("line_height"),row.getString("reader_font"),row.getLong("revision"));}
    public Settings settings(UUID user) {return jdbc.query("SELECT * FROM personal_settings WHERE user_id=?",this::mapSettings,user).stream().findFirst().orElse(Settings.defaults());}
    public Settings settings(UUID user,Settings value) {
        jdbc.update("INSERT INTO personal_settings(user_id) VALUES (?) ON CONFLICT DO NOTHING",user);
        jdbc.update("UPDATE personal_settings SET theme=?,reader_theme=?,font_size=?,line_height=?,reader_font=?,revision=revision+1 WHERE user_id=?",value.theme(),value.readerTheme(),value.fontSize(),value.lineHeight(),value.readerFont(),user);
        return settings(user);
    }
    public Settings replaySettings(UUID user,UUID key) {return jdbc.queryForObject("SELECT result->>'theme' AS theme,result->>'readerTheme' AS reader_theme,(result->>'fontSize')::integer AS font_size,(result->>'lineHeight')::numeric AS line_height,result->>'readerFont' AS reader_font,(result->>'revision')::bigint AS revision FROM personal_mutations WHERE user_id=? AND mutation_key=?",this::mapSettings,user,key);}
    public void receipt(UUID user,UUID key,String fingerprint,Settings value) {
        jdbc.update("INSERT INTO personal_mutations(user_id,mutation_key,fingerprint,result) VALUES (?,?,?,jsonb_build_object('theme',?::text,'readerTheme',?::text,'fontSize',?::integer,'lineHeight',?::numeric,'readerFont',?::text,'revision',?::bigint))",user,key,fingerprint,value.theme(),value.readerTheme(),value.fontSize(),value.lineHeight(),value.readerFont(),value.revision());
    }
}
