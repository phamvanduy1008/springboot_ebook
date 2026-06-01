package com.example.epub.core.repository.catalog;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="EPUB_IDENTITY_DB_TEST",matches="true")
class CatalogSchemaIT {
    @Test void upgradesIdentityAndProtectsOwnershipVersionPointersAndReadyObjects() {
        var root=new JdbcTemplate(new DriverManagerDataSource(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD")));
        String schema="catalog_test_"+UUID.randomUUID().toString().replace("-","");
        root.execute("CREATE SCHEMA "+schema);
        String url=System.getenv("DB_URL")+(System.getenv("DB_URL").contains("?")?"&":"?")+"currentSchema="+schema;
        var datasource=new DriverManagerDataSource(url,System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"));
        var jdbc=new JdbcTemplate(datasource);
        try {
            Flyway.configure().dataSource(datasource).schemas(schema).defaultSchema(schema).target("4").load().migrate();
            UUID user=UUID.randomUUID();jdbc.update("INSERT INTO users(id,email_normalized,password_hash,display_name) VALUES (?,'schema@example.test','hash','Schema')",user);
            var flyway=Flyway.configure().dataSource(datasource).schemas(schema).defaultSchema(schema).load();flyway.migrate();flyway.validate();
            assertEquals(user,jdbc.queryForObject("SELECT id FROM users WHERE email_normalized='schema@example.test'",UUID.class));
            UUID a=book(jdbc,"Đường Đến Thái Hư"),b=book(jdbc,"Đường Đến Thái Hư");assertNotEquals(a,b);
            assertEquals("duong den thai hu",jdbc.queryForObject("SELECT catalog_fold_v1(?)",String.class,"Đường Đến Thái Hư"));
            assertEquals("duong den thai hu tac gia",jdbc.queryForObject("SELECT search_text FROM books WHERE id=?",String.class,a));
            assertThrows(DataIntegrityViolationException.class,() -> jdbc.update("INSERT INTO books(visibility,title,author_display,slug) VALUES ('PRIVATE','Private','A','private')"));
            UUID privateBook=UUID.randomUUID();jdbc.update("INSERT INTO books(id,owner_id,visibility,title,author_display,slug) VALUES (?,?,'PRIVATE','Private','A','private')",privateBook,user);
            assertThrows(DataIntegrityViolationException.class,() -> jdbc.update("UPDATE books SET visibility='PUBLIC',owner_id=NULL WHERE id=?",privateBook));
            UUID av=version(jdbc,a,1),bv=version(jdbc,b,1);
            assertThrows(DataIntegrityViolationException.class,() -> version(jdbc,a,1));
            assertThrows(DataIntegrityViolationException.class,() -> jdbc.update("UPDATE book_versions SET state='READY' WHERE id=?",av));
            assertThrows(DataIntegrityViolationException.class,() -> jdbc.update("UPDATE books SET current_version_id=? WHERE id=?",av,a));
            ready(jdbc,a,av);ready(jdbc,b,bv);
            assertThrows(DataIntegrityViolationException.class,() -> jdbc.update("UPDATE books SET published_version_id=? WHERE id=?",bv,a));
            jdbc.update("UPDATE books SET current_version_id=?,published_version_id=?,status='PUBLISHED' WHERE id=?",av,av,a);
            assertThrows(DataIntegrityViolationException.class,() -> jdbc.update("UPDATE book_versions SET extractor_version='changed' WHERE id=?",av));
            assertThrows(DataIntegrityViolationException.class,() -> jdbc.update("UPDATE stored_objects SET state='DELETING' WHERE book_version_id=?",av));
            assertThrows(DataIntegrityViolationException.class,() -> jdbc.update("DELETE FROM stored_objects WHERE book_version_id=?",av));
            UUID cv=version(jdbc,privateBook,1);ready(jdbc,privateBook,cv);
            assertThrows(DataIntegrityViolationException.class,() -> jdbc.update("UPDATE books SET published_version_id=?,status='PUBLISHED' WHERE id=?",cv,privateBook));
            assertThrows(DataIntegrityViolationException.class,() -> jdbc.update("INSERT INTO stored_objects(book_id,book_version_id,kind,object_key,sha256,size_bytes,media_type) VALUES (?,?,'COVER','../escape',?,1,'image/png')",a,av,"a".repeat(64)));
            assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM books",Integer.class));
        } finally { root.execute("DROP SCHEMA "+schema+" CASCADE"); }
    }
    private UUID book(JdbcTemplate jdbc,String title) {
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO books(id,visibility,title,author_display,slug) VALUES (?,'PUBLIC',?,'Tác giả','duong-den-thai-hu')",id,title);return id;
    }
    private UUID version(JdbcTemplate jdbc,UUID book,int number) {
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO book_versions(id,book_id,version_no) VALUES (?,?,?)",id,book,number);return id;
    }
    private void ready(JdbcTemplate jdbc,UUID book,UUID version) {
        jdbc.update("UPDATE book_versions SET state='PROCESSING' WHERE id=?",version);
        UUID source=UUID.randomUUID(),reader=UUID.randomUUID();
        for(var object:new UUID[]{source,reader})jdbc.update("INSERT INTO stored_objects(id,book_id,book_version_id,kind,object_key,sha256,size_bytes,media_type,state) VALUES (?,?,?,?,?,?,1,'application/epub+zip','READY')",
            object,book,version,object.equals(source)?"SOURCE_EPUB":"READER_EPUB","test/"+object,"a".repeat(64));
        UUID raw=UUID.randomUUID(),resource=UUID.randomUUID(),spine=UUID.randomUUID();
        for(UUID object:new UUID[]{raw,resource})jdbc.update("INSERT INTO stored_objects(id,book_id,book_version_id,kind,object_key,sha256,size_bytes,media_type,state) VALUES (?,?,?,?,?,?,1,?,'READY')",object,book,version,object.equals(raw)?"RAW_TEXT":"RESOURCE","test/"+object,"c".repeat(64),object.equals(raw)?"text/plain":"application/xhtml+xml");
        jdbc.update("INSERT INTO reader_resources(book_version_id,book_id,href,object_id) VALUES (?,?,'EPUB/r0.xhtml',?)",version,book,resource);
        jdbc.update("INSERT INTO spine_items(id,book_version_id,book_id,spine_index,href,raw_text_object_id,raw_utf16_length) VALUES (?,?,?,0,'EPUB/r0.xhtml',?,1)",spine,version,book,raw);
        jdbc.update("INSERT INTO toc_entries(book_version_id,book_id,ordinal,label,spine_item_id) VALUES (?,?,0,'Test chapter',?)",version,book,spine);
        jdbc.update("UPDATE book_versions SET source_object_id=?,reader_object_id=?,extractor_version='test',sanitizer_version='test',ready_at=now(),state='READY' WHERE id=?",source,reader,version);
    }
}
