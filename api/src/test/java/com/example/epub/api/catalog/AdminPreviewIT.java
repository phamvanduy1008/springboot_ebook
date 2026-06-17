package com.example.epub.api.catalog;

import com.example.epub.api.ApiApplication;
import com.example.epub.core.service.epub.IngestService;
import com.example.epub.core.service.jobs.JobService;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.example.epub.api.catalog.CatalogTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="EPUB_IDENTITY_DB_TEST",matches="true")
class AdminPreviewIT {
    @TempDir Path root;

    @Test void adminCanPreviewOnlySanitizedReadyPublicVersionsBeforePublishing() throws Exception {
        try(var context=new SpringApplicationBuilder(ApiApplication.class).run("--spring.profiles.active=local","--server.port=0","--app.local-seed.enabled=false","--app.storage-root="+root,"--logging.level.root=WARN","--debug=false");var fixture=new CatalogTestSupport(context.getBean(JdbcTemplate.class))) {
            int port=((WebServerApplicationContext)context).getWebServer().getPort();
            UUID admin=fixture.user("ADMIN"),user=fixture.user("USER"),book=fixture.publicDraft("Preview draft","Writer",null);
            try(var a=new Browser(port);var u=new Browser(port);var guest=new Browser(port)) {
                try {
                    a.login(admin);u.login(user);
                    var upload=EpubUploadIT.send(a,"POST","/api/v1/admin/books/"+book+"/epub?revision=0",UUID.randomUUID(),EpubTestFiles.book(),true);
                    assertEquals(202,upload.statusCode(),upload.body());
                    UUID version=UUID.fromString(JSON.readTree(upload.body()).get("versionId").asText());
                    String base="/api/v1/admin/books/"+book+"/versions/"+version+"/preview";
                    assertEquals(404,a.call("GET",base+"/manifest",null,false).statusCode());
                    var jobs=context.getBean(JobService.class);var ingest=context.getBean(IngestService.class);
                    var lease=jobs.claim("INGEST").orElseThrow();var prepared=ingest.prepare(lease);
                    try {jobs.complete(lease,prepared);}finally {((AutoCloseable)prepared).close();}
                    assertEquals(404,a.call("GET","/api/v1/books/"+book+"/versions/"+version+"/manifest",null,false).statusCode());
                    assertEquals(401,guest.call("GET",base+"/manifest",null,false).statusCode());
                    assertEquals(403,u.call("GET",base+"/manifest",null,false).statusCode());
                    var manifest=a.call("GET",base+"/manifest",null,false);
                    assertEquals(200,manifest.statusCode(),manifest.body());
                    assertEquals("private, no-store",manifest.headers().firstValue("Cache-Control").orElseThrow());
                    var data=JSON.readTree(manifest.body());
                    assertEquals(version.toString(),data.get("versionId").asText());
                    assertTrue(data.get("reader").get("url").asText().startsWith(base));
                    assertFalse(manifest.body().contains("object_key"));
                    assertFalse(manifest.body().contains("SOURCE_EPUB"));
                    String chapter=base+"/chapters/"+data.get("toc").get(0).get("id").asText();
                    var section=a.call("GET",chapter,null,false);
                    assertEquals(200,section.statusCode());
                    assertTrue(section.body().contains("Xin"));
                    assertEquals(base+"/resources/",JSON.readTree(section.body()).get("baseUrl").asText());
                    String resource=data.get("spine").get(0).get("resourceUrl").asText();
                    assertEquals(200,a.call("GET",resource,null,false).statusCode());
                    assertEquals(403,u.call("GET",resource,null,false).statusCode());
                    assertEquals(404,a.call("GET",base+"/resources/secret.txt",null,false).statusCode());
                    assertEquals(404,a.call("GET","/api/v1/admin/books/"+UUID.randomUUID()+"/versions/"+version+"/preview/manifest",null,false).statusCode());
                    fixture.jdbc.update("UPDATE users SET role='USER' WHERE id=?",admin);
                    assertEquals(403,a.call("GET",base+"/manifest",null,false).statusCode());
                } finally {EpubUploadIT.clearUploadRelations(fixture);}
            }
        }
    }
}
