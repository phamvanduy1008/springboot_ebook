package com.example.epub.api.catalog;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

final class CatalogTestSupport implements AutoCloseable {
    static final String PASSWORD="Catalog test password 123";
    static final JsonMapper JSON=JsonMapper.builder().build();
    final JdbcTemplate jdbc;
    final List<UUID> books=new ArrayList<>(),categories=new ArrayList<>(),authors=new ArrayList<>(),users=new ArrayList<>();
    private final List<Map<String,Object>> loginBucket;
    private static String authHash(String text) {
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    CatalogTestSupport(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        // The isolated reactor runs HTTP suites sequentially against one cluster. Restore only
        // our shared loopback login bucket so catalog logins do not consume later auth tests' budget.
        loginBucket=jdbc.queryForList("SELECT window_started,hits FROM auth_rate_limits WHERE bucket_key=?",authHash("login-ip:127.0.0.1"));
    }
    UUID user(String role) {
        UUID id=UUID.randomUUID();users.add(id);
        jdbc.update("INSERT INTO users(id,email_normalized,password_hash,display_name,role,email_verified_at) VALUES (?,?,?,?,?,now())",id,id+"@example.test",new BCryptPasswordEncoder(12).encode(PASSWORD),"Catalog test",role);return id;
    }
    UUID privateBook(UUID owner) {
        UUID id=UUID.randomUUID();books.add(id);jdbc.update("INSERT INTO books(id,owner_id,visibility,title,author_display,slug) VALUES (?,?,'PRIVATE','Private catalog sentinel','Private author','private-test')",id,owner);return id;
    }
    UUID publicDraft(String title,String author,Integer year) {
        UUID id=UUID.randomUUID();books.add(id);jdbc.update("INSERT INTO books(id,visibility,title,author_display,slug,publication_year) VALUES (?,'PUBLIC',? ,?,'public-test',?)",id,title,author,year);return id;
    }
    UUID publish(UUID book) {
        var found=jdbc.queryForList("SELECT id FROM book_versions WHERE book_id=? ORDER BY version_no DESC LIMIT 1",UUID.class,book);
        UUID version=found.isEmpty()?UUID.randomUUID():found.getFirst();
        if(!found.isEmpty()&&"READY".equals(jdbc.queryForObject("SELECT state FROM book_versions WHERE id=?",String.class,version))) {jdbc.update("UPDATE books SET published_version_id=?,current_version_id=?,status='PUBLISHED' WHERE id=?",version,version,book);return version;}
        if(found.isEmpty())jdbc.update("INSERT INTO book_versions(id,book_id,version_no,state) VALUES (?,?,1,'PROCESSING')",version,book);
        else jdbc.update("UPDATE book_versions SET state='PROCESSING' WHERE id=?",version);
        UUID source=UUID.randomUUID(),reader=UUID.randomUUID();
        for(UUID object:List.of(source,reader))jdbc.update("INSERT INTO stored_objects(id,book_id,book_version_id,kind,object_key,sha256,size_bytes,media_type,state) VALUES (?,?,?,?,?,?,1,'application/epub+zip','READY')",
            object,book,version,object.equals(source)?"SOURCE_EPUB":"READER_EPUB","test/"+object,"b".repeat(64));
        UUID raw=UUID.randomUUID(),resource=UUID.randomUUID(),spine=UUID.randomUUID();
        for(UUID object:List.of(raw,resource))jdbc.update("INSERT INTO stored_objects(id,book_id,book_version_id,kind,object_key,sha256,size_bytes,media_type,state) VALUES (?,?,?,?,?,?,1,?,'READY')",object,book,version,object.equals(raw)?"RAW_TEXT":"RESOURCE","test/"+object,"c".repeat(64),object.equals(raw)?"text/plain":"application/xhtml+xml");
        jdbc.update("INSERT INTO reader_resources(book_version_id,book_id,href,object_id) VALUES (?,?,'EPUB/r0.xhtml',?)",version,book,resource);
        jdbc.update("INSERT INTO spine_items(id,book_version_id,book_id,spine_index,href,raw_text_object_id,raw_utf16_length) VALUES (?,?,?,0,'EPUB/r0.xhtml',?,1)",spine,version,book,raw);
        jdbc.update("INSERT INTO toc_entries(book_version_id,book_id,ordinal,label,spine_item_id) VALUES (?,?,0,'Test chapter',?)",version,book,spine);
        jdbc.update("UPDATE book_versions SET source_object_id=?,reader_object_id=?,extractor_version='test',sanitizer_version='test',ready_at=now(),state='READY' WHERE id=?",source,reader,version);
        jdbc.update("UPDATE books SET published_version_id=?,current_version_id=?,status='PUBLISHED' WHERE id=?",version,version,book);return version;
    }
    @Override public void close() {
        var transactions=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        transactions.executeWithoutResult(tx -> {
            for(UUID book:books) {
                jdbc.update("UPDATE books SET status='DELETED',deleted_at=now(),published_version_id=NULL,current_version_id=NULL WHERE id=?",book);
                jdbc.execute("SET CONSTRAINTS version_source_fk,version_reader_fk,version_cover_fk DEFERRED");
                jdbc.update("DELETE FROM toc_entries WHERE book_id=?",book);jdbc.update("DELETE FROM spine_items WHERE book_id=?",book);jdbc.update("DELETE FROM reader_resources WHERE book_id=?",book);
                jdbc.update("DELETE FROM stored_objects WHERE book_id=?",book);jdbc.update("DELETE FROM book_versions WHERE book_id=?",book);
                jdbc.update("DELETE FROM books WHERE id=?",book);jdbc.update("DELETE FROM audit_logs WHERE target_id=?",book);
            }
            for(UUID id:categories) {jdbc.update("DELETE FROM categories WHERE id=?",id);jdbc.update("DELETE FROM audit_logs WHERE target_id=?",id);}
            for(UUID id:authors) {jdbc.update("DELETE FROM authors WHERE id=?",id);jdbc.update("DELETE FROM audit_logs WHERE target_id=?",id);}
            for(UUID id:users) {jdbc.update("DELETE FROM audit_logs WHERE target_id=? OR actor_id=?",id,id);jdbc.update("DELETE FROM spring_session WHERE principal_name=?",id.toString());jdbc.update("DELETE FROM users WHERE id=?",id);jdbc.update("DELETE FROM auth_rate_limits WHERE bucket_key=?",authHash("login-email:"+id+"@example.test"));}
            String key=authHash("login-ip:127.0.0.1");jdbc.update("DELETE FROM auth_rate_limits WHERE bucket_key=?",key);
            if(!loginBucket.isEmpty()) {var saved=loginBucket.getFirst();jdbc.update("INSERT INTO auth_rate_limits(bucket_key,window_started,hits) VALUES (?,?,?)",key,saved.get("window_started"),saved.get("hits"));}
        });
    }
    static final class Browser implements AutoCloseable {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        final int port;String token;
        Browser(int port) {this.port=port;}
        HttpResponse<String> call(String method,String path,Object body,boolean csrf) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
            if(csrf)request.header("X-CSRF-TOKEN",token);
            if(body!=null)request.header("Content-Type","application/json");
            return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        void csrf() throws Exception {var response=call("GET","/api/v1/auth/csrf",null,false);assertEquals(200,response.statusCode());token=JSON.readTree(response.body()).get("token").asText();}
        void login(UUID user) throws Exception {csrf();assertEquals(200,call("POST","/api/v1/auth/login",Map.of("email",user+"@example.test","password",PASSWORD),true).statusCode());csrf();}
        @Override public void close() {client.close();}
    }
}
