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
class ReaderSchemaIT {
    @Test void readerRowsHaveVersionScopedParentsRawTextAndReadyImmutability() {
        var admin=new JdbcTemplate(new DriverManagerDataSource(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD")));
        String schema="reader_test_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
        var ds=new DriverManagerDataSource(System.getenv("DB_URL")+(System.getenv("DB_URL").contains("?")?"&":"?")+"currentSchema="+schema,System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"));var db=new JdbcTemplate(ds);
        try {
            Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).load().migrate();
            UUID book=UUID.randomUUID(),version=UUID.randomUUID(),raw=UUID.randomUUID(),spine=UUID.randomUUID(),parent=UUID.randomUUID();
            db.update("INSERT INTO books(id,visibility,title,author_display,slug) VALUES (?,'PUBLIC','Reader','Writer','reader')",book);
            db.update("INSERT INTO book_versions(id,book_id,version_no,state) VALUES (?,?,1,'PROCESSING')",version,book);
            db.update("INSERT INTO stored_objects(id,book_id,book_version_id,kind,object_key,sha256,size_bytes,media_type,state) VALUES (?,?,?,'RAW_TEXT',?,?,0,'text/plain','READY')",raw,book,version,"test/"+raw,"a".repeat(64));
            db.update("INSERT INTO spine_items(id,book_version_id,book_id,spine_index,href,raw_text_object_id,raw_utf16_length) VALUES (?,?,?,0,'EPUB/r0.xhtml',?,0)",spine,version,book,raw);
            db.update("INSERT INTO toc_entries(id,book_version_id,book_id,ordinal,label,spine_item_id,fragment) VALUES (?,?,?,0,'One',?,'one')",parent,version,book,spine);
            db.update("INSERT INTO toc_entries(book_version_id,book_id,parent_id,ordinal,label,spine_item_id,fragment) VALUES (?,?,?,1,'Two',?,'two')",version,book,parent,spine);
            assertEquals(2,db.queryForObject("SELECT count(*) FROM toc_entries",Integer.class));assertEquals(1,db.queryForObject("SELECT count(*) FROM spine_items",Integer.class));
            assertThrows(DataIntegrityViolationException.class,()->db.update("UPDATE toc_entries SET parent_id=?,ordinal=0 WHERE ordinal=1",parent));
            assertThrows(DataIntegrityViolationException.class,()->db.update("UPDATE toc_entries SET parent_id=? WHERE id=?",parent,parent));
            UUID source=UUID.randomUUID(),reader=UUID.randomUUID();for(UUID id:new UUID[]{source,reader})db.update("INSERT INTO stored_objects(id,book_id,book_version_id,kind,object_key,sha256,size_bytes,media_type,state) VALUES (?,?,?,?,?,?,1,'application/epub+zip','READY')",id,book,version,id.equals(source)?"SOURCE_EPUB":"READER_EPUB","test/"+id,"b".repeat(64));
            UUID resource=UUID.randomUUID();db.update("INSERT INTO stored_objects(id,book_id,book_version_id,kind,object_key,sha256,size_bytes,media_type,state) VALUES (?,?,?,'RESOURCE',?,?,0,'application/xhtml+xml','READY')",resource,book,version,"test/"+resource,"c".repeat(64));
            db.update("INSERT INTO reader_resources(book_version_id,book_id,href,object_id) VALUES (?,?,'EPUB/r0.xhtml',?)",version,book,resource);
            db.update("UPDATE book_versions SET source_object_id=?,reader_object_id=?,extractor_version='test',sanitizer_version='test',ready_at=now(),state='READY' WHERE id=?",source,reader,version);
            assertThrows(DataIntegrityViolationException.class,()->db.update("UPDATE spine_items SET title='changed' WHERE id=?",spine));
            assertThrows(DataIntegrityViolationException.class,()->db.update("DELETE FROM toc_entries WHERE ordinal=1"));
        } finally {admin.execute("DROP SCHEMA "+schema+" CASCADE");}
    }
}
