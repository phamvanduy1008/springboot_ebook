
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
class StoryOverviewIT {
 @Test void editorialIsBookScopedRevisionProtectedAndOverviewUsesPublishedMetadataOnly() throws Exception {
  try(var context=new SpringApplicationBuilder(ApiApplication.class).run("--spring.profiles.active=local","--server.port=0","--app.local-seed.enabled=false","--logging.level.root=WARN","--debug=false");var f=new CatalogTestSupport(context.getBean(JdbcTemplate.class))) {
   int port=((WebServerApplicationContext)context).getWebServer().getPort();UUID admin=f.user("ADMIN"),user=f.user("USER"),book=f.publicDraft("Overview","Author",null),other=f.publicDraft("Other","Author",null),draft=f.publicDraft("Draft","Author",null),privateId=f.privateBook(user);
   f.publish(book);UUID version=UUID.randomUUID();f.jdbc.update("INSERT INTO book_versions(id,book_id,version_no,state,chapter_count) VALUES (?,?,1,'PROCESSING',27)",version,other);f.publish(other);
   UUID cat=UUID.randomUUID();f.categories.add(cat);f.jdbc.update("INSERT INTO categories(id,name,slug) VALUES (?,'Category',?)",cat,"overview-"+cat);for(UUID id:List.of(book,other,draft,privateId))f.jdbc.update("INSERT INTO book_categories(book_id,category_id) VALUES (?,?)",id,cat);
   try(var a=new Browser(port);var u=new Browser(port);var guest=new Browser(port)) {
    a.login(admin);u.login(user);String editorial="/api/v1/admin/books/"+book+"/editorial";
    assertEquals(403,u.call("PATCH",editorial,Map.of("revision",0,"quote","Denied"),true).statusCode());assertEquals(403,a.call("PATCH",editorial,Map.of("revision",0,"quote","Denied"),false).statusCode());
    var quote=a.call("PATCH",editorial,Map.of("revision",0,"quote","Trích dẫn riêng"),true);assertEquals(200,quote.statusCode(),quote.body());assertEquals(409,a.call("PATCH",editorial,Map.of("revision",0,"quote","Stale"),true).statusCode());
    var chars=a.call("PUT","/api/v1/admin/books/"+book+"/characters",Map.of("revision",1,"characters",List.of(Map.of("name","Nhân vật","role","Chính","description","Mô tả","initials","NV","color","#123456"))),true);assertEquals(200,chars.statusCode(),chars.body());String characterId=JSON.readTree(chars.body()).get("characters").get(0).get("id").asText();
    assertEquals(400,a.call("PUT","/api/v1/admin/books/"+other+"/characters",Map.of("revision",0,"characters",List.of(Map.of("id",characterId,"name","Foreign"))),true).statusCode());
    assertEquals(404,a.call("GET","/api/v1/admin/books/"+privateId+"/editorial",null,false).statusCode());
    var overview=guest.call("GET","/api/v1/books/"+book+"/overview",null,false);assertEquals(200,overview.statusCode(),overview.body());var data=JSON.readTree(overview.body());assertEquals("Trích dẫn riêng",data.get("quote").asText());assertEquals(1,data.get("characters").size());assertTrue(data.get("chapterCount").isNull());assertTrue(data.get("metrics").get("averageRating").isNull());assertTrue(data.get("viewRank").isNull());assertEquals(1,data.get("related").size());assertEquals(other.toString(),data.get("related").get(0).get("id").asText());assertEquals(0,data.get("distribution").get(0).asLong());
    var second=JSON.readTree(guest.call("GET","/api/v1/books/"+other+"/overview",null,false).body());assertEquals(27,second.get("chapterCount").asInt());assertEquals(0,second.get("characters").size());assertTrue(second.get("quote").isNull());
    assertEquals(0,f.jdbc.queryForObject("SELECT count(*) FROM book_interactions WHERE book_id=?",Integer.class,book));
    for(UUID id:List.of(privateId,draft,UUID.randomUUID()))assertEquals(404,guest.call("GET","/api/v1/books/"+id+"/overview",null,false).statusCode());
    assertEquals(2,f.jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_id=? AND action IN ('BOOK_QUOTE_UPDATED','BOOK_CHARACTERS_UPDATED')",Integer.class,book));
   }
  }
 }
}
