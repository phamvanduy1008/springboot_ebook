package com.example.epub.api.catalog;
import com.example.epub.api.ApiApplication;
import com.example.epub.core.service.epub.IngestService;
import com.example.epub.core.service.jobs.JobService;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.epub.api.catalog.CatalogTestSupport.*;
@EnabledIfEnvironmentVariable(named="EPUB_IDENTITY_DB_TEST",matches="true")
class PublicationIT {
    @TempDir Path root;
    private UUID upload(Browser browser,UUID book) throws Exception {var result=EpubUploadIT.send(browser,"POST","/api/v1/admin/books/"+book+"/epub?revision=0",UUID.randomUUID(),EpubTestFiles.book(),true);assertEquals(202,result.statusCode(),result.body());return UUID.fromString(JSON.readTree(result.body()).get("versionId").asText());}
    private void finish(JobService jobs,IngestService ingest) throws Exception {var lease=jobs.claim("INGEST").orElseThrow();var prepared=ingest.prepare(lease);try {jobs.complete(lease,prepared);}finally {((AutoCloseable)prepared).close();}}
    private java.net.http.HttpResponse<String> change(Browser browser,UUID book,UUID version,long revision,UUID key,boolean csrf) throws Exception {
        var body=version==null?Map.of("revision",revision):Map.of("revision",revision,"versionId",version.toString());
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+browser.port+"/api/v1/admin/books/"+book+(version==null?"/unpublish":"/publish"))).header("Content-Type","application/json").header("Idempotency-Key",key.toString());if(csrf)request.header("X-CSRF-TOKEN",browser.token);
        return browser.client.send(request.POST(java.net.http.HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
    }
    @Test void onlyVerifiedSameBookReadyArtifactsPublishWithRevisionIdempotencyAndAudit() throws Exception {
        try(var context=new SpringApplicationBuilder(ApiApplication.class).run("--spring.profiles.active=local","--server.port=0","--app.local-seed.enabled=false","--app.storage-root="+root,"--logging.level.root=WARN","--debug=false");var f=new CatalogTestSupport(context.getBean(JdbcTemplate.class))) {
            int port=((WebServerApplicationContext)context).getWebServer().getPort();UUID admin=f.user("ADMIN"),user=f.user("USER"),aBook=f.publicDraft("Publication A","Writer",null),bBook=f.publicDraft("Publication B","Writer",null),privateBook=f.privateBook(user);
            var jobs=context.getBean(JobService.class);var ingest=context.getBean(IngestService.class);
            try(var a=new Browser(port);var b=new Browser(port);var guest=new Browser(port)) {
                try {
                    a.login(admin);b.login(user);UUID aVersion=upload(a,aBook);
                    assertEquals(409,change(a,aBook,aVersion,1,UUID.randomUUID(),true).statusCode());finish(jobs,ingest);
                    UUID bVersion=upload(a,bBook);finish(jobs,ingest);
                    assertEquals(409,change(a,aBook,bVersion,2,UUID.randomUUID(),true).statusCode());assertEquals(403,change(b,aBook,aVersion,2,UUID.randomUUID(),true).statusCode());assertEquals(403,change(a,aBook,aVersion,2,UUID.randomUUID(),false).statusCode());
                    assertEquals(404,change(a,privateBook,aVersion,0,UUID.randomUUID(),true).statusCode());
                    String reader=f.jdbc.queryForObject("SELECT o.object_key FROM book_versions v JOIN stored_objects o ON o.id=v.reader_object_id WHERE v.id=?",String.class,aVersion);Path file=root.resolve(reader);byte[] original=Files.readAllBytes(file);UUID key=UUID.randomUUID();
                    Files.write(file,new byte[]{0});try {assertEquals(503,change(a,aBook,aVersion,2,key,true).statusCode());}finally {Files.write(file,original);}
                    assertEquals("DRAFT",f.jdbc.queryForObject("SELECT status FROM books WHERE id=?",String.class,aBook));
                    var published=change(a,aBook,aVersion,2,key,true);assertEquals(200,published.statusCode(),published.body());assertEquals(published.body(),change(a,aBook,aVersion,2,key,true).body());
                    assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_id=? AND action='BOOK_PUBLISHED'",Integer.class,aBook));
                    assertEquals(409,change(a,aBook,bVersion,2,key,true).statusCode());assertEquals(409,change(a,aBook,aVersion,2,UUID.randomUUID(),true).statusCode());
                    assertEquals(200,guest.call("GET","/api/v1/books/"+aBook,null,false).statusCode());assertEquals(404,guest.call("GET","/api/v1/books/"+bBook,null,false).statusCode());
                    UUID hideKey=UUID.randomUUID();var hidden=change(a,aBook,null,3,hideKey,true);assertEquals(200,hidden.statusCode());assertEquals(hidden.body(),change(a,aBook,null,3,hideKey,true).body());
                    assertEquals(404,guest.call("GET","/api/v1/books/"+aBook,null,false).statusCode());assertEquals("READY",f.jdbc.queryForObject("SELECT state FROM book_versions WHERE id=?",String.class,aVersion));assertTrue(Files.exists(file));
                    assertEquals(published.body(),change(a,aBook,aVersion,2,key,true).body());assertEquals("HIDDEN",f.jdbc.queryForObject("SELECT status FROM books WHERE id=?",String.class,aBook));
                    assertEquals(200,change(a,aBook,aVersion,4,UUID.randomUUID(),true).statusCode());
                    try(var threads=Executors.newFixedThreadPool(2)) {
                        var publish=threads.submit(()->change(a,aBook,aVersion,5,UUID.randomUUID(),true));var hide=threads.submit(()->change(a,aBook,null,5,UUID.randomUUID(),true));var statuses=new ArrayList<>(List.of(publish.get(20,TimeUnit.SECONDS).statusCode(),hide.get(20,TimeUnit.SECONDS).statusCode()));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
                    }
                    assertEquals(6L,f.jdbc.queryForObject("SELECT revision FROM books WHERE id=?",Long.class,aBook));
                    assertEquals(204,a.call("DELETE","/api/v1/admin/books/"+bBook,Map.of("revision",2),true).statusCode());assertEquals(404,change(a,bBook,bVersion,3,UUID.randomUUID(),true).statusCode());
                } finally {EpubUploadIT.clearUploadRelations(f);}
            }
        }
    }
}
