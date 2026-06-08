package com.example.epub.api.catalog;
import com.example.epub.api.ApiApplication;
import com.example.epub.core.service.epub.*;
import com.example.epub.core.service.jobs.JobService;
import com.example.epub.core.service.identity.AccountService;
import com.example.epub.core.model.epub.EpubModels.Actor;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
class ReaderDeliveryIT {
    @TempDir Path root;
    private String contentText(byte[] bytes) throws Exception {
        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        return factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes)).getDocumentElement().getTextContent();
    }
    private HttpResponse<byte[]> get(Browser browser,String method,String path,String etag,String range) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+browser.port+path)).timeout(java.time.Duration.ofSeconds(20));if(etag!=null)request.header("If-None-Match",etag);if(range!=null)request.header("Range",range);
        return browser.client.send(request.method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    private UUID version(HttpResponse<String> response) throws Exception {assertEquals(202,response.statusCode(),response.body());return UUID.fromString(JSON.readTree(response.body()).get("versionId").asText());}
    private void finish(JobService jobs,IngestService ingest) throws Exception {var lease=jobs.claim("INGEST").orElseThrow();var prepared=ingest.prepare(lease);try {jobs.complete(lease,prepared);}finally {((AutoCloseable)prepared).close();}}
    @Test void manifestsAndEveryAssetRespectPublicationOwnershipRevalidationAndImmutableArtifacts() throws Exception {
        try(var context=new SpringApplicationBuilder(ApiApplication.class).run("--spring.profiles.active=local","--server.port=0","--app.local-seed.enabled=false","--app.storage-root="+root,"--logging.level.root=WARN","--debug=false");var f=new CatalogTestSupport(context.getBean(JdbcTemplate.class))) {
            int port=((WebServerApplicationContext)context).getWebServer().getPort();UUID admin=f.user("ADMIN"),owner=f.user("USER"),other=f.user("USER"),book=f.publicDraft("Reader API","Writer",null);byte[] source=EpubTestFiles.book();
            var jobs=context.getBean(JobService.class);var ingest=context.getBean(IngestService.class);var publication=context.getBean(PublicationService.class);var account=context.getBean(AccountService.class).byId(admin).orElseThrow();var actor=new Actor(admin,account.passwordChangedAt());
            try(var a=new Browser(port);var b=new Browser(port);var c=new Browser(port);var guest=new Browser(port)) {
                try {
                    a.login(admin);b.login(owner);c.login(other);UUID published=version(EpubUploadIT.send(a,"POST","/api/v1/admin/books/"+book+"/epub?revision=0",UUID.randomUUID(),source,true));finish(jobs,ingest);
                    String base="/api/v1/books/"+book,explicit=base+"/versions/"+published;
                    assertEquals(404,get(guest,"GET",explicit+"/manifest",null,null).statusCode());assertEquals(404,get(a,"GET",explicit+"/reader.epub",null,null).statusCode());publication.change(actor,book,published,2,UUID.randomUUID(),true,"reader-test");
                    var manifest=get(guest,"GET",base+"/manifest",null,null);assertEquals(200,manifest.statusCode());String manifestTag=manifest.headers().firstValue("ETag").orElseThrow();var data=JSON.readTree(manifest.body());assertEquals(published.toString(),data.get("versionId").asText());assertEquals(1,data.get("spine").size());assertEquals(2,data.get("toc").size());
                    assertEquals(data.get("toc").get(0).get("id").asText(),data.get("toc").get(1).get("parentId").asText());assertEquals(data.get("toc").get(0).get("spineItemId"),data.get("toc").get(1).get("spineItemId"));assertEquals("two",data.get("toc").get(1).get("fragment").asText());
                    String text=new String(manifest.body(),StandardCharsets.UTF_8);assertFalse(text.contains("objects/"));assertFalse(text.contains("object_key"));assertFalse(text.contains("SOURCE_EPUB"));
                    assertEquals(304,get(guest,"GET",base+"/manifest",manifestTag,null).statusCode());
                    String epub=data.get("reader").get("url").asText(),chapter=data.get("spine").get(0).get("resourceUrl").asText();var file=get(guest,"GET",epub,null,"bytes=0-9");assertEquals(200,file.statusCode());assertEquals("none",file.headers().firstValue("Accept-Ranges").orElseThrow());String tag=file.headers().firstValue("ETag").orElseThrow();
                    assertEquals(data.get("reader").get("sha256").asText(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.body())));assertEquals(data.get("reader").get("sizeBytes").asLong(),file.body().length);
                    assertEquals(304,get(guest,"GET",epub,"W/"+tag,null).statusCode());var head=get(guest,"HEAD",epub,null,null);assertEquals(200,head.statusCode());assertEquals(0,head.body().length);assertEquals(file.body().length,Long.parseLong(head.headers().firstValue("Content-Length").orElseThrow()));
                    var html=get(guest,"GET",chapter,null,null);assertEquals(200,html.statusCode());assertTrue(contentText(html.body()).contains("Xin chào 😀"));assertFalse(new String(html.body(),StandardCharsets.UTF_8).contains("<script"));assertEquals("SAMEORIGIN",html.headers().firstValue("X-Frame-Options").orElseThrow());assertTrue(html.headers().firstValue("Content-Security-Policy").orElseThrow().contains("script-src 'none'"));
                    assertEquals(200,get(guest,"GET",base+"/cover",null,null).statusCode());assertTrue(JSON.readTree(guest.call("GET",base,null,false).body()).get("readerAvailable").asBoolean());
                    assertEquals(404,get(guest,"GET",explicit+"/source.epub",null,null).statusCode());assertEquals(404,get(guest,"GET",explicit+"/resources/secret.txt",null,null).statusCode());
                    String sourceKey=f.jdbc.queryForObject("SELECT o.object_key FROM book_versions v JOIN stored_objects o ON o.id=v.source_object_id WHERE v.id=?",String.class,published);byte[] originalSource=Files.readAllBytes(root.resolve(sourceKey));Files.write(root.resolve(sourceKey),new byte[]{0});try {assertEquals(200,get(guest,"GET",epub,null,null).statusCode());}finally {Files.write(root.resolve(sourceKey),originalSource);}
                    String readerKey=f.jdbc.queryForObject("SELECT o.object_key FROM book_versions v JOIN stored_objects o ON o.id=v.reader_object_id WHERE v.id=?",String.class,published);Files.write(root.resolve(readerKey),new byte[]{0});try {assertEquals(503,get(guest,"GET",epub,tag,null).statusCode());}finally {Files.write(root.resolve(readerKey),file.body());}
                    var imported=EpubUploadIT.send(b,"POST","/api/v1/imports",UUID.randomUUID(),source,true);UUID privateVersion=version(imported),privateBook=UUID.fromString(JSON.readTree(imported.body()).get("bookId").asText());f.books.add(privateBook);finish(jobs,ingest);
                    String personal="/api/v1/books/"+privateBook,privateBase=personal+"/versions/"+privateVersion;var privateManifest=get(b,"GET",personal+"/manifest",null,null);assertEquals(200,privateManifest.statusCode());assertEquals("private, no-store",privateManifest.headers().firstValue("Cache-Control").orElseThrow());assertEquals("Cookie",privateManifest.headers().firstValue("Vary").orElseThrow());String privateTag=privateManifest.headers().firstValue("ETag").orElseThrow();var privateData=JSON.readTree(privateManifest.body());String privateChapter=privateData.get("spine").get(0).get("resourceUrl").asText();
                    assertEquals(304,get(b,"GET",personal+"/manifest",privateTag,null).statusCode());assertEquals(200,get(b,"GET",privateChapter,null,null).statusCode());assertEquals(200,get(b,"GET",personal+"/cover",null,null).statusCode());
                    for(var denied:List.of(guest,c,a))for(String route:List.of(personal+"/manifest?userId="+owner,privateBase+"/manifest",privateBase+"/reader.epub",privateBase+"/cover",privateChapter))assertEquals(404,get(denied,"GET",route,privateTag,"bytes=0-9").statusCode());
                    var own=JSON.readTree(b.call("GET","/api/v1/library?ownership=owned",null,false).body()).get("items");assertTrue(own.toString().contains("\"readerAvailable\":true"));
                    UUID replacement=version(EpubUploadIT.send(a,"POST",base.replace("/books/","/admin/books/")+"/epub?revision=3",UUID.randomUUID(),source,true));finish(jobs,ingest);assertEquals(404,get(guest,"GET",base+"/versions/"+replacement+"/manifest",null,null).statusCode());publication.change(actor,book,replacement,5,UUID.randomUUID(),true,"reader-republish");
                    assertEquals(404,get(guest,"GET",epub,tag,null).statusCode());assertEquals(200,get(guest,"GET",base+"/manifest",manifestTag,null).statusCode());assertEquals("READY",f.jdbc.queryForObject("SELECT state FROM book_versions WHERE id=?",String.class,published));
                    String current=base+"/versions/"+replacement;publication.change(actor,book,null,6,UUID.randomUUID(),false,"reader-hide");for(String route:List.of(base+"/manifest",current+"/manifest",current+"/reader.epub",base+"/cover"))assertEquals(404,get(guest,"GET",route,tag,"bytes=0-9").statusCode());
                    f.jdbc.update("UPDATE books SET status='DELETED',deleted_at=now() WHERE id=?",privateBook);assertEquals(404,get(b,"GET",personal+"/manifest",privateTag,null).statusCode());assertEquals(404,get(b,"GET",privateChapter,null,"bytes=0-9").statusCode());
                } finally {EpubUploadIT.clearUploadRelations(f);}
            }
        }
    }
}
