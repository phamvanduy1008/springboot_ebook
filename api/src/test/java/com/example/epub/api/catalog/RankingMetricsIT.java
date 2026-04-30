
package com.example.epub.api.catalog;
import com.example.epub.api.ApiApplication;
import com.example.epub.core.model.catalog.MetricWindow;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.epub.api.catalog.CatalogTestSupport.*;
@EnabledIfEnvironmentVariable(named="EPUB_IDENTITY_DB_TEST",matches="true")
class RankingMetricsIT {
 @Test void serverViewsDedupeThrottleWindowAggregationAndAuthorIdentity() throws Exception {
  try(var context=new SpringApplicationBuilder(ApiApplication.class).run("--spring.profiles.active=local","--server.port=0","--app.local-seed.enabled=false","--logging.level.root=WARN","--debug=false");var f=new CatalogTestSupport(context.getBean(JdbcTemplate.class));var b=new Browser(((WebServerApplicationContext)context).getWebServer().getPort())) {
   UUID first=f.publicDraft("Metric first","Same author",null),second=f.publicDraft("Metric second","Same author",null),draft=f.publicDraft("Draft","Same author",null);f.publish(first);f.publish(second);
   UUID a1=UUID.randomUUID(),a2=UUID.randomUUID();
   for(UUID id:List.of(a1,a2)){f.authors.add(id);f.jdbc.update("INSERT INTO authors(id,name,slug) VALUES (?,'Same author',?)",id,"metric-"+id);}
   f.jdbc.update("INSERT INTO book_authors(book_id,author_id,position) VALUES (?,?,0),(?,?,0)",first,a1,second,a2);
   assertEquals(403,b.call("POST","/api/v1/books/"+first+"/views",Map.of(),false).statusCode());b.csrf();
   assertEquals(400,b.call("POST","/api/v1/books/"+first+"/views",Map.of("count",99999),true).statusCode());assertEquals(404,b.call("POST","/api/v1/books/"+draft+"/views",Map.of(),true).statusCode());
   var posted=b.call("POST","/api/v1/books/"+first+"/views",Map.of(),true);assertEquals(200,posted.statusCode(),posted.body());assertTrue(JSON.readTree(posted.body()).get("recorded").asBoolean());assertFalse(JSON.readTree(b.call("POST","/api/v1/books/"+first+"/views",Map.of(),true).body()).get("recorded").asBoolean());
   var window=MetricWindow.of("month",Clock.systemUTC());
   for(var event:List.of(window.from().minusNanos(1000000),window.from(),window.to()))f.jdbc.update("INSERT INTO book_interactions(book_id,kind,viewer_hash,occurred_at) VALUES (?,'VIEW',?,?)",first,UUID.randomUUID().toString().replace("-","").repeat(2),Timestamp.from(event));
   var result=JSON.readTree(b.call("GET","/api/v1/rankings?metric=views&period=month",null,false).body());assertEquals(2,result.get("total").asLong());assertEquals(first.toString(),result.get("items").get(0).get("book").get("id").asText());assertEquals(2,result.get("items").get(0).get("metrics").get("views").asLong());assertEquals(0,result.get("items").get(0).get("metrics").get("reads").asLong());assertTrue(result.get("items").get(0).get("metrics").get("averageRating").isNull());
   var authors=JSON.readTree(b.call("GET","/api/v1/rankings/authors?metric=views",null,false).body());assertEquals(2,authors.get("total").asLong());assertEquals(a1.toString(),authors.get("items").get(0).get("id").asText());assertNotEquals(authors.get("items").get(0).get("id"),authors.get("items").get(1).get("id"));
   assertEquals(0,JSON.readTree(b.call("GET","/api/v1/rankings?metric=rating",null,false).body()).get("total").asLong());assertEquals(400,b.call("GET","/api/v1/rankings?period=bad",null,false).statusCode());
   var reads=JSON.readTree(b.call("GET","/api/v1/rankings?metric=reads&size=1&page=0",null,false).body());var readsNext=JSON.readTree(b.call("GET","/api/v1/rankings?metric=reads&size=1&page=1",null,false).body());assertNotEquals(reads.get("items").get(0).get("book").get("id"),readsNext.get("items").get(0).get("book").get("id"));
   f.jdbc.update("UPDATE books SET status='HIDDEN' WHERE id=?",first);var hidden=JSON.readTree(b.call("GET","/api/v1/rankings?metric=views",null,false).body());assertEquals(1,hidden.get("total").asLong());assertEquals(second.toString(),hidden.get("items").get(0).get("book").get("id").asText());
   boolean throttled=false;for(int i=0;i<65;i++){if(b.call("POST","/api/v1/books/"+second+"/views",Map.of(),true).statusCode()==429){throttled=true;break;}}assertTrue(throttled);
   assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM book_interactions WHERE book_id=?",Integer.class,second));
  }
 }
}
