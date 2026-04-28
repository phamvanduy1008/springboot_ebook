package com.example.epub.api.catalog;
import com.example.epub.api.ApiApplication;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.epub.api.catalog.CatalogTestSupport.*;
@EnabledIfEnvironmentVariable(named="EPUB_IDENTITY_DB_TEST",matches="true")
class PublicCatalogIT {
 @Test void guestDiscoverySearchPaginationAndAllPublicPoliciesUsePublishedReadyVersions() throws Exception {
  try(var context=new SpringApplicationBuilder(ApiApplication.class).run("--spring.profiles.active=local","--server.port=0","--app.local-seed.enabled=false","--logging.level.root=WARN","--debug=false");var f=new CatalogTestSupport(context.getBean(JdbcTemplate.class));var b=new Browser(((WebServerApplicationContext)context).getWebServer().getPort())) {
   var empty=b.call("GET","/api/v1/books",null,false);assertEquals(200,empty.statusCode());assertEquals(0,JSON.readTree(empty.body()).get("total").asLong());
   UUID owner=f.user("USER"),privateId=f.privateBook(owner),draft=f.publicDraft("Đường Đến Thái Hư","Tác giả",2025),first=f.publicDraft("Đường Đến Thái Hư","Tác giả",2026),second=f.publicDraft("Đường Đến Thái Hư","Tác giả",2026),hidden=f.publicDraft("Hidden sentinel","Author",2024);
   f.publish(first);f.publish(second);f.publish(hidden);f.jdbc.update("UPDATE books SET status='HIDDEN' WHERE id=?",hidden);
   UUID cat=UUID.randomUUID();f.categories.add(cat);f.jdbc.update("INSERT INTO categories(id,name,slug) VALUES (?,'Tiên hiệp',?)",cat,"test-"+cat);
   for(UUID id:List.of(first,second,draft,privateId,hidden))f.jdbc.update("INSERT INTO book_categories(book_id,category_id) VALUES (?,?)",id,cat);
   for(String term:List.of("ĐƯỜNG","duong den","tac gia")) {
    var r=b.call("GET","/api/v1/books?q="+URLEncoder.encode(term,StandardCharsets.UTF_8),null,false);assertEquals(200,r.statusCode(),r.body());assertEquals(2,JSON.readTree(r.body()).get("total").asLong());
   }
   String query="/api/v1/books?category="+cat+"&year=2026&sort=title,asc&size=1&page=";
   var p0=JSON.readTree(b.call("GET",query+0,null,false).body());var p1=JSON.readTree(b.call("GET",query+1,null,false).body());assertEquals(2,p0.get("total").asLong());assertNotEquals(p0.get("items").get(0).get("id"),p1.get("items").get(0).get("id"));assertEquals(0,JSON.readTree(b.call("GET",query+2,null,false).body()).get("items").size());
   assertEquals(400,b.call("GET","/api/v1/books?sort=title;DROP",null,false).statusCode());assertEquals(400,b.call("GET","/api/v1/books?size=101",null,false).statusCode());
   for(UUID id:List.of(privateId,draft,hidden,UUID.randomUUID()))assertEquals(404,b.call("GET","/api/v1/books/"+id,null,false).statusCode());
   var detail=b.call("GET","/api/v1/books/"+first,null,false);assertEquals(200,detail.statusCode());assertEquals("no-store",detail.headers().firstValue("Cache-Control").orElse(""));assertFalse(detail.body().contains("object_key"));assertFalse(detail.body().contains("publishedVersionId"));assertFalse(JSON.readTree(detail.body()).get("readerAvailable").asBoolean());
   assertEquals(2,JSON.readTree(b.call("GET","/api/v1/categories/"+cat,null,false).body()).get("bookCount").asLong());assertEquals(2,JSON.readTree(b.call("GET","/api/v1/categories/"+cat+"/books",null,false).body()).get("total").asLong());assertEquals(404,b.call("GET","/api/v1/categories/"+UUID.randomUUID()+"/books",null,false).statusCode());
   var home=JSON.readTree(b.call("GET","/api/v1/home",null,false).body());assertEquals(2,home.get("bookCount").asLong());assertEquals(0,home.get("suggestions").size());assertEquals(0,home.get("trending").size());assertEquals(0,home.get("authors").size());
   f.jdbc.update("UPDATE books SET status='HIDDEN' WHERE id=?",first);assertEquals(404,b.call("GET","/api/v1/books/"+first,null,false).statusCode());assertEquals(1,JSON.readTree(b.call("GET","/api/v1/categories/"+cat,null,false).body()).get("bookCount").asLong());
   var filters=b.call("GET","/api/v1/books/filters",null,false);assertEquals(200,filters.statusCode());assertFalse(filters.body().contains("2024"));assertFalse(filters.body().contains("2025"));
  }
 }
}
