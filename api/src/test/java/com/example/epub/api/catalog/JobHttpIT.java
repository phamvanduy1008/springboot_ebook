package com.example.epub.api.catalog;
import com.example.epub.api.ApiApplication;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.epub.api.catalog.CatalogTestSupport.*;
@EnabledIfEnvironmentVariable(named="EPUB_IDENTITY_DB_TEST",matches="true")
class JobHttpIT {
    @Test void jobsAreSessionScopedAndRedactedIncludingAdministratorRequests() throws Exception {
        try(var context=new SpringApplicationBuilder(ApiApplication.class).run("--spring.profiles.active=local","--server.port=0","--app.local-seed.enabled=false","--logging.level.root=WARN","--debug=false");var f=new CatalogTestSupport(context.getBean(JdbcTemplate.class))) {
            int port=((WebServerApplicationContext)context).getWebServer().getPort();UUID owner=f.user("USER"),other=f.user("USER"),admin=f.user("ADMIN"),book=f.privateBook(owner),job=UUID.randomUUID();
            try(var a=new Browser(port);var b=new Browser(port);var c=new Browser(port);var guest=new Browser(port)) {
                f.jdbc.update("INSERT INTO jobs(id,owner_id,book_id,type,dedupe_key,payload_json,last_error_code) VALUES (?,?,?,'INGEST',?,?::jsonb,?)",job,owner,book,job.toString(),"{\"private_path\":\"sentinel-secret\"}","sentinel-secret/path");
                try {
                    String route="/api/v1/jobs/"+job;
                    assertEquals(401,guest.call("GET",route,null,false).statusCode());a.login(owner);b.login(other);c.login(admin);
                    var response=a.call("GET",route,null,false);assertEquals(200,response.statusCode());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());assertFalse(response.body().contains("sentinel-secret"));assertFalse(response.body().contains("payload"));assertFalse(response.body().contains("lease"));
                    assertEquals(404,b.call("GET",route,null,false).statusCode());assertEquals(404,c.call("GET",route,null,false).statusCode());
                } finally {f.jdbc.update("DELETE FROM jobs WHERE id=?",job);}
            }
        }
    }
}
