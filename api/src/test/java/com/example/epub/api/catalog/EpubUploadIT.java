package com.example.epub.api.catalog;
import com.example.epub.api.ApiApplication;
import com.example.epub.core.integration.storage.ObjectStorage;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
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
class EpubUploadIT {
    @TempDir Path root;
    static HttpResponse<String> send(Browser browser,String method,String route,UUID key,byte[] bytes,boolean csrf) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+browser.port+route)).timeout(java.time.Duration.ofSeconds(30)).header("Content-Type","application/epub+zip");
        if(key!=null)request.header("Idempotency-Key",key.toString());if(csrf)request.header("X-CSRF-TOKEN",browser.token);
        return browser.client.send(request.method(method,HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofString());
    }
    static void clearUploadRelations(CatalogTestSupport f) {
        for(UUID user:f.users) {f.jdbc.update("DELETE FROM epub_upload_receipts WHERE user_id=?",user);f.jdbc.update("DELETE FROM quota_reservations WHERE user_id=?",user);f.jdbc.update("DELETE FROM quota_buckets WHERE user_id=?",user);}
        for(UUID book:f.books)f.jdbc.update("DELETE FROM jobs WHERE book_id=?",book);
    }
    @Test void uploadsAreAsyncIdempotentQuotaBoundAndSessionAuthorized() throws Exception {
        try(var context=new SpringApplicationBuilder(ApiApplication.class).run("--spring.profiles.active=local","--server.port=0","--app.local-seed.enabled=false","--app.storage-root="+root,"--app.epub.private-quota-bytes=100000","--logging.level.root=WARN","--debug=false");var f=new CatalogTestSupport(context.getBean(JdbcTemplate.class))) {
            int port=((WebServerApplicationContext)context).getWebServer().getPort();UUID admin=f.user("ADMIN"),owner=f.user("USER"),other=f.user("USER"),unverified=f.user("USER"),book=f.publicDraft("Upload test","Writer",null);
            f.jdbc.update("UPDATE users SET email_verified_at=NULL WHERE id=?",unverified);byte[] bytes=EpubTestFiles.book(false,true);
            try(var a=new Browser(port);var b=new Browser(port);var c=new Browser(port);var d=new Browser(port);var guest=new Browser(port)) {
                try {
                    a.login(admin);b.login(owner);c.login(other);d.login(unverified);guest.csrf();
                    assertEquals(401,send(guest,"POST","/api/v1/imports",UUID.randomUUID(),bytes,true).statusCode());
                    assertEquals(403,send(b,"POST","/api/v1/imports",UUID.randomUUID(),bytes,false).statusCode());
                    assertEquals(403,send(d,"POST","/api/v1/imports",UUID.randomUUID(),bytes,true).statusCode());
                    String publicRoute="/api/v1/admin/books/"+book+"/epub?revision=0";
                    assertEquals(403,send(b,"POST",publicRoute,UUID.randomUUID(),bytes,true).statusCode());
                    assertEquals(400,send(a,"POST",publicRoute,null,bytes,true).statusCode());
                    assertEquals(422,send(a,"POST",publicRoute,UUID.randomUUID(),new byte[]{1,2,3},true).statusCode());
                    assertEquals(422,send(a,"POST",publicRoute,UUID.randomUUID(),new byte[0],true).statusCode());
                    UUID key=UUID.randomUUID();var accepted=send(a,"POST",publicRoute,key,bytes,true);assertEquals(202,accepted.statusCode(),accepted.body());
                    var value=JSON.readTree(accepted.body());UUID version=UUID.fromString(value.get("versionId").asText());
                    assertEquals("PROCESSING",f.jdbc.queryForObject("SELECT state FROM book_versions WHERE id=?",String.class,version));
                    assertEquals(accepted.body(),send(a,"POST",publicRoute,key,bytes,true).body());
                    byte[] changed=EpubTestFiles.book(true);assertEquals(409,send(a,"POST",publicRoute,key,changed,true).statusCode());
                    assertEquals(409,send(a,"POST",publicRoute,UUID.randomUUID(),bytes,true).statusCode());
                    assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM book_versions WHERE book_id=?",Integer.class,book));
                    String object=f.jdbc.queryForObject("SELECT o.object_key FROM stored_objects o WHERE o.book_version_id=?",String.class,version);assertEquals(bytes.length,context.getBean(ObjectStorage.class).stat(object).size());
                    UUID privateKey=UUID.randomUUID();
                    try(var threads=Executors.newFixedThreadPool(2)) {
                        var one=threads.submit(()->send(b,"POST","/api/v1/imports",privateKey,bytes,true));var two=threads.submit(()->send(b,"POST","/api/v1/imports",privateKey,bytes,true));
                        var p=one.get(30,TimeUnit.SECONDS);var q=two.get(30,TimeUnit.SECONDS);assertEquals(202,p.statusCode(),p.body());assertEquals(p.body(),q.body());
                        UUID privateBook=UUID.fromString(JSON.readTree(p.body()).get("bookId").asText());f.books.add(privateBook);
                        assertEquals("PRIVATE",f.jdbc.queryForObject("SELECT visibility FROM books WHERE id=?",String.class,privateBook));
                        assertEquals(owner,f.jdbc.queryForObject("SELECT owner_id FROM books WHERE id=?",UUID.class,privateBook));
                        assertEquals(404,send(c,"PUT","/api/v1/library/books/"+privateBook+"/epub?revision=1",UUID.randomUUID(),bytes,true).statusCode());
                        assertEquals(404,send(a,"POST","/api/v1/admin/books/"+privateBook+"/epub?revision=1",UUID.randomUUID(),bytes,true).statusCode());
                    }
                    f.jdbc.update("UPDATE quota_buckets SET limit_units=used_units+reserved_units WHERE user_id=?",owner);
                    assertEquals(429,send(b,"POST","/api/v1/imports",UUID.randomUUID(),bytes,true).statusCode());
                    assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM books WHERE owner_id=?",Integer.class,owner));
                    assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM quota_reservations WHERE user_id=?",Integer.class,owner));
                    var user=context.getBean(com.example.epub.core.service.identity.AccountService.class).byId(owner).orElseThrow();
                    var oversized=new java.io.InputStream() {
                        long remaining=(long)com.example.epub.core.service.epub.UploadService.MAX_UPLOAD_BYTES+1;
                        @Override public int read() {return remaining-->0?0:-1;}
                        @Override public int read(byte[] buffer,int offset,int length) {if(remaining<=0)return -1;int count=(int)Math.min(remaining,length);Arrays.fill(buffer,offset,offset+count,(byte)0);remaining-=count;return count;}
                    };
                    var failure=assertThrows(com.example.epub.core.service.epub.EpubFailure.class,()->context.getBean(com.example.epub.core.service.epub.UploadService.class).upload(new com.example.epub.core.model.epub.EpubModels.Actor(owner,user.passwordChangedAt()),null,false,0,UUID.randomUUID(),"Oversized","Writer","",oversized));
                    assertEquals(com.example.epub.core.service.epub.EpubFailure.Reason.TOO_LARGE,failure.reason());
                    try(var staged=java.nio.file.Files.list(root.resolve("staging"));var objects=java.nio.file.Files.list(root.resolve("objects"))) {assertEquals(0,staged.count());assertEquals(2,objects.count());}
                } finally {clearUploadRelations(f);}
            }
        }
    }
}
