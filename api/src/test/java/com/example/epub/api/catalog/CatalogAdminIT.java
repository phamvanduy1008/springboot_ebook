package com.example.epub.api.catalog;

import com.example.epub.api.ApiApplication;
import com.example.epub.core.service.catalog.CatalogAdminService;
import com.example.epub.core.service.identity.AccessPolicy;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.epub.api.catalog.CatalogTestSupport.*;

@EnabledIfEnvironmentVariable(named="EPUB_IDENTITY_DB_TEST",matches="true")
class CatalogAdminIT {
    @Test void metadataCrudRevisionRacesAuditAndPrivateIsolationAreEnforcedOverHttp() throws Exception {
        try(var context=new SpringApplicationBuilder(ApiApplication.class).run("--spring.profiles.active=local","--server.port=0","--app.local-seed.enabled=false","--logging.level.root=WARN","--debug=false");
            var fixture=new CatalogTestSupport(context.getBean(JdbcTemplate.class))) {
            int port=((WebServerApplicationContext)context).getWebServer().getPort();UUID admin=fixture.user("ADMIN"),user=fixture.user("USER");
            try(var a=new Browser(port);var u=new Browser(port);var guest=new Browser(port)) {
                assertEquals(401,guest.call("GET","/api/v1/admin/books",null,false).statusCode());a.login(admin);u.login(user);
                assertEquals(403,u.call("GET","/api/v1/admin/books",null,false).statusCode());
                assertThrows(AccessPolicy.Denied.class,() -> context.getBean(CatalogAdminService.class).books(user,new com.example.epub.core.model.catalog.CatalogQuery(null,null,null,null,0,20,null)));
                assertEquals(403,a.call("POST","/api/v1/admin/categories",Map.of("name","Books"),false).statusCode());
                var categoryResponse=a.call("POST","/api/v1/admin/categories",Map.of("name","Tiểu thuyết","slug","tieu-thuyet","groupName","Văn học"),true);
                assertEquals(201,categoryResponse.statusCode(),categoryResponse.body());UUID category=UUID.fromString(JSON.readTree(categoryResponse.body()).get("id").asText());fixture.categories.add(category);
                assertEquals(409,a.call("POST","/api/v1/admin/categories",Map.of("name","Duplicate","slug","tieu-thuyet"),true).statusCode());
                var authorIds=new ArrayList<UUID>();
                for(int i=0;i<2;i++) {var created=a.call("POST","/api/v1/admin/authors",Map.of("name","Tác giả trùng"),true);assertEquals(201,created.statusCode(),created.body());var id=UUID.fromString(JSON.readTree(created.body()).get("id").asText());authorIds.add(id);fixture.authors.add(id);}
                assertNotEquals(authorIds.get(0),authorIds.get(1));
                var draft=new HashMap<String,Object>();draft.put("title","Đường Đến Thái Hư");draft.put("authorDisplay","Author");draft.put("authorIds",authorIds);draft.put("categoryIds",List.of(category));draft.put("tags",List.of("Tiên hiệp"));draft.put("rightsNote","Test rights");
                var mass=new HashMap<>(draft);mass.put("status","PUBLISHED");mass.put("ownerId",user);
                assertEquals(400,a.call("POST","/api/v1/admin/books",mass,true).statusCode());
                assertEquals(403,u.call("POST","/api/v1/admin/books",draft,true).statusCode());
                var created=a.call("POST","/api/v1/admin/books",draft,true);assertEquals(201,created.statusCode(),created.body());
                var json=JSON.readTree(created.body());UUID book=UUID.fromString(json.get("id").asText());fixture.books.add(book);
                assertEquals("DRAFT",json.get("status").asText());assertEquals("UPLOADING",json.get("versionState").asText());assertEquals("Test rights",json.get("versions").get(0).get("rightsNote").asText());
                assertFalse(created.body().contains("source_object_id"));assertFalse(created.body().contains("password"));
                draft.put("revision",0);draft.put("title","Metadata changed");
                var updated=a.call("PATCH","/api/v1/admin/books/"+book,draft,true);assertEquals(200,updated.statusCode(),updated.body());
                assertEquals(409,a.call("PATCH","/api/v1/admin/books/"+book,draft,true).statusCode());
                assertEquals(409,a.call("DELETE","/api/v1/admin/categories/"+category,Map.of("revision",0),true).statusCode());
                assertEquals(400,a.call("PATCH","/api/v1/admin/books/"+book,Map.of("title","","authorDisplay","A","revision",1),true).statusCode());
                UUID privateBook=fixture.privateBook(user);assertEquals(404,a.call("GET","/api/v1/admin/books/"+privateBook,null,false).statusCode());
                assertFalse(a.call("GET","/api/v1/admin/books",null,false).body().contains(privateBook.toString()));
                var rename=a.call("PATCH","/api/v1/admin/authors/"+authorIds.get(0),Map.of("name","Đổi tên tác giả","revision",0),true);assertEquals(200,rename.statusCode(),rename.body());
                var detail=a.call("GET","/api/v1/admin/books/"+book,null,false);assertTrue(detail.body().contains("Đổi tên tác giả"));assertEquals("no-store",detail.headers().firstValue("Cache-Control").orElse(""));
                long revision=JSON.readTree(detail.body()).get("revision").asLong();
                var cloned=a.call("POST","/api/v1/admin/books/"+book+"/clone",Map.of("revision",revision),true);assertEquals(201,cloned.statusCode(),cloned.body());
                var clone=JSON.readTree(cloned.body());UUID cloneId=UUID.fromString(clone.get("id").asText());fixture.books.add(cloneId);assertNotEquals(book,cloneId);assertEquals("DRAFT",clone.get("status").asText());assertEquals("",clone.get("versions").get(0).get("rightsNote").asText());
                assertEquals(201,a.call("POST","/api/v1/admin/books/"+book+"/versions",Map.of("revision",revision,"rightsNote","New draft"),true).statusCode());
                revision=JSON.readTree(a.call("GET","/api/v1/admin/books/"+book,null,false).body()).get("revision").asLong();draft.put("revision",revision);draft.remove("rightsNote");
                try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                    var left=new HashMap<>(draft);left.put("title","Left update");var right=new HashMap<>(draft);right.put("title","Right update");
                    var first=executor.submit(() -> a.call("PATCH","/api/v1/admin/books/"+book,left,true).statusCode());var second=executor.submit(() -> a.call("PATCH","/api/v1/admin/books/"+book,right,true).statusCode());
                    assertEquals(List.of(200,409),java.util.stream.Stream.of(first.get(),second.get()).sorted().toList());
                }
                assertTrue(fixture.jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_id=? AND target_type='BOOK'",Long.class,book)>=4);
                fixture.publish(book);
                var countView=JSON.readTree(a.call("GET","/api/v1/admin/books/"+book,null,false).body());
                assertEquals(1,countView.get("authors").get(0).get("bookCount").asLong());
                assertEquals(1,countView.get("categories").get(0).get("bookCount").asLong());
                revision=JSON.readTree(a.call("GET","/api/v1/admin/books/"+book,null,false).body()).get("revision").asLong();
                var hidden=a.call("POST","/api/v1/admin/books/"+book+"/hide",Map.of("revision",revision),true);assertEquals(200,hidden.statusCode(),hidden.body());assertEquals("HIDDEN",JSON.readTree(hidden.body()).get("status").asText());
                revision=JSON.readTree(hidden.body()).get("revision").asLong();
                var readyClone=a.call("POST","/api/v1/admin/books/"+book+"/clone",Map.of("revision",revision),true);assertEquals(201,readyClone.statusCode(),readyClone.body());
                UUID readyCloneId=UUID.fromString(JSON.readTree(readyClone.body()).get("id").asText());fixture.books.add(readyCloneId);assertEquals("UPLOADING",JSON.readTree(readyClone.body()).get("versionState").asText());
                assertEquals(0,fixture.jdbc.queryForObject("SELECT count(*) FROM stored_objects WHERE book_id=?",Integer.class,readyCloneId));
                assertEquals(404,a.call("POST","/api/v1/admin/books/"+book+"/publish",Map.of("revision",0),true).statusCode());
                revision=JSON.readTree(a.call("GET","/api/v1/admin/books/"+book,null,false).body()).get("revision").asLong();assertEquals(204,a.call("DELETE","/api/v1/admin/books/"+book,Map.of("revision",revision),true).statusCode());
                assertEquals(404,a.call("GET","/api/v1/admin/books/"+book,null,false).statusCode());
                fixture.jdbc.update("UPDATE users SET role='USER' WHERE id=?",admin);assertEquals(403,a.call("GET","/api/v1/admin/books",null,false).statusCode());
            }
        }
    }
}
