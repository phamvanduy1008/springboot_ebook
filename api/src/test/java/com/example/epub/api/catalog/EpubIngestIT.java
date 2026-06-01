package com.example.epub.api.catalog;
import com.example.epub.api.ApiApplication;
import com.example.epub.core.service.epub.IngestService;
import com.example.epub.core.service.jobs.*;
import com.example.epub.core.integration.storage.ObjectStorage;
import com.example.epub.core.model.jobs.JobModels.Lease;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.epub.api.catalog.CatalogTestSupport.*;
@EnabledIfEnvironmentVariable(named="EPUB_IDENTITY_DB_TEST",matches="true")
class EpubIngestIT {
    @TempDir Path root;
    private void finish(JobService jobs,IngestService ingest,Lease lease) throws Exception {
        Runnable prepared=ingest.prepare(lease);try {jobs.complete(lease,prepared);}finally {((AutoCloseable)prepared).close();}
    }
    private UUID version(java.net.http.HttpResponse<String> response) throws Exception {assertEquals(202,response.statusCode(),response.body());return UUID.fromString(JSON.readTree(response.body()).get("versionId").asText());}
    @Test void actualUploadsBecomeVerifiedImmutableReadyAndRecoverAcrossLeaseAndQuotaFailures() throws Exception {
        try(var context=new SpringApplicationBuilder(ApiApplication.class).run("--spring.profiles.active=local","--server.port=0","--app.local-seed.enabled=false","--app.storage-root="+root,"--logging.level.root=WARN","--debug=false");var f=new CatalogTestSupport(context.getBean(JdbcTemplate.class))) {
            int port=((WebServerApplicationContext)context).getWebServer().getPort();UUID admin=f.user("ADMIN"),owner=f.user("USER"),book=f.publicDraft("Ingest test","Writer",null);byte[] source=EpubTestFiles.book();
            var jobs=context.getBean(JobService.class);var ingest=context.getBean(IngestService.class);var storage=context.getBean(ObjectStorage.class);
            try(var a=new Browser(port);var b=new Browser(port)) {
                try {
                    a.login(admin);b.login(owner);
                    UUID first=version(EpubUploadIT.send(a,"POST","/api/v1/admin/books/"+book+"/epub?revision=0",UUID.randomUUID(),source,true));
                    var lease=jobs.claim("INGEST").orElseThrow();finish(jobs,ingest,lease);
                    assertEquals("READY",f.jdbc.queryForObject("SELECT state FROM book_versions WHERE id=?",String.class,first));
                    assertEquals("DRAFT",f.jdbc.queryForObject("SELECT status FROM books WHERE id=?",String.class,book));assertNull(f.jdbc.queryForObject("SELECT published_version_id FROM books WHERE id=?",UUID.class,book));
                    assertEquals(first,f.jdbc.queryForObject("SELECT current_version_id FROM books WHERE id=?",UUID.class,book));
                    assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM spine_items WHERE book_version_id=?",Integer.class,first));assertEquals(2,f.jdbc.queryForObject("SELECT count(*) FROM toc_entries WHERE book_version_id=?",Integer.class,first));
                    assertEquals(2,f.jdbc.queryForObject("SELECT chapter_count FROM book_versions WHERE id=?",Integer.class,first));
                    var objects=f.jdbc.queryForList("SELECT * FROM stored_objects WHERE book_version_id=?",first);String sourceHash=null,readerHash=null,sourceKey=null;
                    for(var object:objects) {
                        String key=(String)object.get("object_key"),kind=(String)object.get("kind");var info=storage.stat(key);assertEquals(object.get("sha256"),info.sha256());assertEquals(((Number)object.get("size_bytes")).longValue(),info.size());
                        if(kind.equals("SOURCE_EPUB")) {sourceHash=info.sha256();sourceKey=key;}if(kind.equals("READER_EPUB"))readerHash=info.sha256();
                        if(kind.equals("RAW_TEXT"))try(var input=storage.open(key)) {String text=new String(input.readAllBytes(),StandardCharsets.UTF_8);assertTrue(text.contains("Xin chào 😀"));assertEquals(text.length(),f.jdbc.queryForObject("SELECT raw_utf16_length FROM spine_items WHERE raw_text_object_id=?",Integer.class,object.get("id")));assertFalse(text.contains("alert"));}
                    }
                    assertNotEquals(sourceHash,readerHash);assertTrue(jobs.claim("INGEST").isEmpty());
                    assertThrows(JobService.LeaseLost.class,()->jobs.complete(lease,()->fail("Old lease executed")));
                    assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->f.jdbc.update("UPDATE spine_items SET title='changed' WHERE book_version_id=?",first));
                    assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->f.jdbc.update("INSERT INTO stored_objects(book_id,book_version_id,kind,object_key,sha256,size_bytes,media_type,state) VALUES (?,?,'RESOURCE',?,?,1,'text/plain','READY')",book,first,"test/new","a".repeat(64)));
                    assertEquals(((Number)f.jdbc.queryForObject("SELECT sum(size_bytes) FROM stored_objects WHERE book_version_id=?",Number.class,first)).longValue(),f.jdbc.queryForObject("SELECT used_units FROM quota_buckets WHERE user_id=?",Long.class,admin));
                    long revision=f.jdbc.queryForObject("SELECT revision FROM books WHERE id=?",Long.class,book);
                    UUID second=version(EpubUploadIT.send(a,"POST","/api/v1/admin/books/"+book+"/epub?revision="+revision,UUID.randomUUID(),source,true));
                    var abandoned=jobs.claim("INGEST").orElseThrow();var prepared=ingest.prepare(abandoned);
                    f.jdbc.update("UPDATE jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",abandoned.id());jobs.sweep();f.jdbc.update("UPDATE jobs SET available_at=clock_timestamp() WHERE id=?",abandoned.id());
                    var replacement=jobs.claim("INGEST").orElseThrow();assertThrows(JobService.LeaseLost.class,()->jobs.complete(abandoned,prepared));((AutoCloseable)prepared).close();finish(jobs,ingest,replacement);
                    assertEquals(second,f.jdbc.queryForObject("SELECT current_version_id FROM books WHERE id=?",UUID.class,book));assertEquals(2,f.jdbc.queryForObject("SELECT count(*) FROM book_versions WHERE book_id=? AND state='READY'",Integer.class,book));
                    var bad=EpubUploadIT.send(b,"POST","/api/v1/imports",UUID.randomUUID(),EpubTestFiles.book(true),true);UUID badVersion=version(bad);f.books.add(UUID.fromString(JSON.readTree(bad.body()).get("bookId").asText()));
                    var badLease=jobs.claim("INGEST").orElseThrow();var rejected=assertThrows(JobHandler.Rejected.class,()->ingest.prepare(badLease));assertEquals("EPUB_DRM_UNSUPPORTED",rejected.code());jobs.fail(badLease,rejected.code(),false,()->ingest.failed(badLease));
                    assertEquals("FAILED",f.jdbc.queryForObject("SELECT state FROM book_versions WHERE id=?",String.class,badVersion));assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM stored_objects WHERE book_version_id=?",Integer.class,badVersion));
                    assertEquals("RELEASED",f.jdbc.queryForObject("SELECT state FROM quota_reservations WHERE book_version_id=?",String.class,badVersion));
                    var over=EpubUploadIT.send(b,"POST","/api/v1/imports",UUID.randomUUID(),source,true);UUID overVersion=version(over);f.books.add(UUID.fromString(JSON.readTree(over.body()).get("bookId").asText()));
                    f.jdbc.update("UPDATE quota_buckets SET limit_units=used_units+reserved_units WHERE user_id=?",owner);
                    var overLease=jobs.claim("INGEST").orElseThrow();var overPrepared=ingest.prepare(overLease);var quota=assertThrows(JobHandler.Rejected.class,()->jobs.complete(overLease,overPrepared));assertEquals("EPUB_QUOTA_EXCEEDED",quota.code());jobs.fail(overLease,quota.code(),false,()->ingest.failed(overLease));((AutoCloseable)overPrepared).close();
                    assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM stored_objects WHERE book_version_id=?",Integer.class,overVersion));assertEquals(0L,f.jdbc.queryForObject("SELECT used_units+reserved_units FROM quota_buckets WHERE user_id=?",Long.class,owner));
                    f.jdbc.update("UPDATE quota_buckets SET limit_units=1000000 WHERE user_id=?",owner);
                    var crash=EpubUploadIT.send(b,"POST","/api/v1/imports",UUID.randomUUID(),source,true);UUID crashVersion=version(crash);f.books.add(UUID.fromString(JSON.readTree(crash.body()).get("bookId").asText()));
                    var crashLease=jobs.claim("INGEST").orElseThrow();f.jdbc.update("UPDATE jobs SET max_attempts=1,lease_until=clock_timestamp()-interval '1 second' WHERE id=?",crashLease.id());jobs.sweep();
                    var orphan=storage.promote(storage.stage(new ByteArrayInputStream(new byte[]{7}),10));var old=java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofDays(3)));Files.setLastModifiedTime(root.resolve(orphan.key()),old);Files.setLastModifiedTime(root.resolve(sourceKey),old);ingest.maintain();
                    assertFalse(Files.exists(root.resolve(orphan.key())));assertTrue(Files.exists(root.resolve(sourceKey)));assertEquals("FAILED",f.jdbc.queryForObject("SELECT state FROM book_versions WHERE id=?",String.class,crashVersion));
                    try(var files=Files.list(root.resolve("objects"))) {assertEquals(f.jdbc.queryForObject("SELECT count(*) FROM stored_objects WHERE book_id=?",Long.class,book),files.count());}
                } finally {EpubUploadIT.clearUploadRelations(f);}
            }
        }
    }
}
