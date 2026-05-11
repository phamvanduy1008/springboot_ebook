package com.example.epub.api.catalog;

import com.example.epub.api.ApiApplication;
import com.example.epub.core.model.library.LibraryModels.*;
import com.example.epub.core.service.library.LibraryService;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
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
class PersonalLibraryIT {
    @Test void likesUseActiveFavoritesUtcBoundariesAndNeverExposePrivateOrHiddenBooks() {
        try(var context=new SpringApplicationBuilder(ApiApplication.class).run("--spring.profiles.active=local","--server.port=0","--app.local-seed.enabled=false","--logging.level.root=WARN","--debug=false");var f=new CatalogTestSupport(context.getBean(JdbcTemplate.class))) {
            var metrics=context.getBean(com.example.epub.core.repository.catalog.MetricsRepository.class);
            var window=com.example.epub.core.model.catalog.MetricWindow.of("month",java.time.Clock.fixed(java.time.Instant.parse("2026-10-17T12:00:00Z"),java.time.ZoneOffset.UTC));
            UUID book=f.publicDraft("Boundary favorite","Writer",null),hidden=f.publicDraft("Hidden favorite","Writer",null),owner=f.user("USER"),privateBook=f.privateBook(owner);
            f.publish(book);f.publish(hidden);f.jdbc.update("UPDATE books SET status='HIDDEN' WHERE id=?",hidden);
            for(var at:List.of(window.from().minusMillis(1),window.from(),window.to()))f.jdbc.update("INSERT INTO personal_book_state(user_id,book_id,favorite,liked_at) VALUES (?, ?,true,?)",f.user("USER"),book,java.sql.Timestamp.from(at));
            UUID locked=f.user("USER");f.jdbc.update("UPDATE users SET status='LOCKED' WHERE id=?",locked);
            for(var pair:List.of(new UUID[]{owner,privateBook},new UUID[]{owner,hidden},new UUID[]{locked,book}))f.jdbc.update("INSERT INTO personal_book_state(user_id,book_id,favorite,liked_at) VALUES (?,?,true,?)",pair[0],pair[1],java.sql.Timestamp.from(window.from()));
            var page=metrics.books(window,"likes",null,null,0,20);assertEquals(1,page.total());assertEquals(book,page.items().getFirst().id());assertEquals(1,page.items().getFirst().likes());assertEquals(1,metrics.totals(book,window).likes());
            UUID author=UUID.randomUUID();f.authors.add(author);f.jdbc.update("INSERT INTO authors(id,name,slug) VALUES (?,'Favorite author',?)",author,"favorite-"+author);f.jdbc.update("INSERT INTO book_authors(book_id,author_id,position) VALUES (?,?,0)",book,author);
            var authors=metrics.authors(window,"likes",null,0,20);assertEquals(1,authors.total());assertEquals(1,authors.items().getFirst().metrics().likes());
        }
    }
    private HttpResponse<String> call(Browser browser,String method,String path,Object body,UUID key,boolean csrf) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+browser.port+"/api/v1"+path)).timeout(Duration.ofSeconds(20));
        if(csrf)request.header("X-CSRF-TOKEN",browser.token);
        if(key!=null)request.header("Idempotency-Key",key.toString());
        if(body!=null)request.header("Content-Type","application/json");
        return browser.client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private long revision(HttpResponse<String> response) throws Exception {assertEquals(200,response.statusCode(),response.body());return JSON.readTree(response.body()).get("revision").asLong();}
    @Test void sessionIsolationRetryRevisionIndependentFavoritesOwnershipHistoryAndSettings() throws Exception {
        try(var context=new SpringApplicationBuilder(ApiApplication.class).run("--spring.profiles.active=local","--server.port=0","--app.local-seed.enabled=false","--logging.level.root=WARN","--debug=false");var f=new CatalogTestSupport(context.getBean(JdbcTemplate.class))) {
            int port=((WebServerApplicationContext)context).getWebServer().getPort();
            var service=context.getBean(LibraryService.class);
            UUID a=f.user("USER"),b=f.user("USER"),admin=f.user("ADMIN"),one=f.publicDraft("Library public one","Writer",2026),two=f.publicDraft("Library public two","Writer",2026),owned=f.privateBook(a);
            f.publish(one);f.publish(two);
            try(var first=new Browser(port);var second=new Browser(port);var administrator=new Browser(port);var guest=new Browser(port)) {
                assertEquals(401,call(guest,"GET","/library",null,null,false).statusCode());
                first.login(a);second.login(b);administrator.login(admin);
                for(var other:List.of(second,administrator)) {
                    assertEquals(404,call(other,"GET","/library/"+owned,null,null,false).statusCode());
                    assertEquals(404,call(other,"PUT","/favorites/"+owned,Map.of("revision",0),UUID.randomUUID(),true).statusCode());
                }
                assertEquals(403,call(first,"PUT","/library/"+one,Map.of("shelf","want","revision",0),UUID.randomUUID(),false).statusCode());
                assertEquals(400,call(first,"PUT","/library/"+one,Map.of("shelf","want","revision",0),null,true).statusCode());
                assertEquals(400,call(first,"PUT","/library/"+one,Map.of("shelf","private","revision",0),UUID.randomUUID(),true).statusCode());
                assertEquals(400,call(first,"PUT","/library/"+one,Map.of("shelf","want","revision",0,"userId",b),UUID.randomUUID(),true).statusCode());
                UUID saveKey=UUID.randomUUID();
                var saved=call(first,"PUT","/library/"+one,Map.of("shelf","want","revision",0),saveKey,true);assertEquals(1,revision(saved));
                assertEquals(saved.body(),call(first,"PUT","/library/"+one,Map.of("shelf","want","revision",0),saveKey,true).body());
                assertEquals(409,call(first,"PUT","/library/"+one,Map.of("shelf","reading","revision",1),saveKey,true).statusCode());
                assertEquals(409,call(first,"PUT","/library/"+one,Map.of("shelf","reading","revision",0),UUID.randomUUID(),true).statusCode());
                assertEquals(0,revision(call(second,"GET","/library/"+one,null,null,false)));
                var favorite=call(first,"PUT","/favorites/"+two,Map.of("revision",0),UUID.randomUUID(),true);assertEquals(1,revision(favorite));assertTrue(JSON.readTree(favorite.body()).get("shelf").isNull());
                var list=JSON.readTree(call(first,"GET","/library",null,null,false).body());assertEquals(1,list.get("total").asLong());assertEquals(1,list.get("counts").get("favorites").asLong());
                assertEquals(2,revision(call(first,"PUT","/library/"+two,Map.of("shelf","finished","revision",1),UUID.randomUUID(),true)));
                var removed=call(first,"DELETE","/library/"+two+"?revision=2",null,UUID.randomUUID(),true);assertEquals(3,revision(removed));assertTrue(JSON.readTree(removed.body()).get("favorite").asBoolean());
                assertEquals(1,revision(call(first,"PUT","/library/"+owned,Map.of("shelf","reading","revision",0),UUID.randomUUID(),true)));
                assertEquals(2,revision(call(first,"DELETE","/library/"+owned+"?revision=1",null,UUID.randomUUID(),true)));
                assertEquals(409,call(first,"PUT","/library/"+owned,Map.of("shelf","want","revision",0),UUID.randomUUID(),true).statusCode());
                assertEquals(a,f.jdbc.queryForObject("SELECT owner_id FROM books WHERE id=?",UUID.class,owned));
                assertEquals(2,revision(call(first,"PUT","/favorites/"+one,Map.of("revision",1),UUID.randomUUID(),true)));
                UUID likeKey=UUID.randomUUID();assertEquals(1,revision(call(second,"PUT","/favorites/"+one,Map.of("revision",0),likeKey,true)));
                assertEquals(1,revision(call(second,"PUT","/favorites/"+one,Map.of("revision",0),likeKey,true)));
                var ranks=JSON.readTree(call(guest,"GET","/rankings?metric=likes",null,null,false).body());assertEquals(2,ranks.get("total").asLong());assertEquals(one.toString(),ranks.get("items").get(0).get("book").get("id").asText());assertEquals(2,ranks.get("items").get(0).get("metrics").get("likes").asLong());
                assertEquals(2,revision(call(second,"DELETE","/favorites/"+one+"?revision=1",null,UUID.randomUUID(),true)));
                assertEquals(0,revision(call(first,"GET","/settings",null,null,false)));
                var settings=Map.of("theme","dark","readerTheme","sepia","fontSize",24,"lineHeight",1.9,"readerFont","sans","revision",0);
                UUID settingsKey=UUID.randomUUID();assertEquals(1,revision(call(first,"PUT","/settings",settings,settingsKey,true)));assertEquals(1,revision(call(first,"PUT","/settings",settings,settingsKey,true)));
                assertEquals(0,revision(call(second,"GET","/settings",null,null,false)));
                assertEquals(409,call(first,"PUT","/settings",settings,UUID.randomUUID(),true).statusCode());
                var invalid=new HashMap<String,Object>(settings);invalid.put("role","ADMIN");assertEquals(400,call(first,"PUT","/settings",invalid,UUID.randomUUID(),true).statusCode());
                invalid.remove("role");invalid.put("fontSize",200);assertEquals(400,call(first,"PUT","/settings",invalid,UUID.randomUUID(),true).statusCode());
                assertEquals(404,call(first,"POST","/view-history/"+UUID.randomUUID(),Map.of("revision",0),UUID.randomUUID(),true).statusCode());
                UUID historyKey=UUID.randomUUID();assertEquals(3,revision(call(first,"POST","/view-history/"+one,Map.of("revision",2),historyKey,true)));assertEquals(3,revision(call(first,"POST","/view-history/"+one,Map.of("revision",2),historyKey,true)));
                assertEquals(1,JSON.readTree(call(first,"GET","/view-history",null,null,false).body()).get("total").asLong());
                assertEquals(0,JSON.readTree(call(second,"GET","/view-history",null,null,false).body()).get("total").asLong());
                Actor actor=new Actor(a,f.jdbc.queryForObject("SELECT password_changed_at FROM users WHERE id=?",java.sql.Timestamp.class,a).toInstant());
                UUID concurrent=f.publicDraft("Concurrent library book","Writer",null);f.publish(concurrent);UUID concurrentKey=UUID.randomUUID();
                try(var pool=Executors.newFixedThreadPool(2)) {
                    var attempts=pool.invokeAll(List.of(()->service.mutate(actor,concurrent,"save","want",0,concurrentKey),()->service.mutate(actor,concurrent,"save","want",0,concurrentKey)));
                    assertEquals(attempts.get(0).get(),attempts.get(1).get());
                }
                assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM personal_book_state WHERE user_id=? AND book_id=?",Integer.class,a,concurrent));
                for(int i=0;i<101;i++)service.mutate(actor,f.privateBook(a),"view",null,0,UUID.randomUUID());
                var history=JSON.readTree(call(first,"GET","/view-history?size=100",null,null,false).body());assertEquals(100,history.get("total").asLong());assertEquals(100,history.get("items").size());
                var newest=history.get("items").get(0);UUID historyBook=UUID.fromString(newest.get("book").get("id").asText());
                assertEquals(newest.get("revision").asLong()+1,revision(call(first,"DELETE","/view-history/"+historyBook+"?revision="+newest.get("revision").asLong(),null,UUID.randomUUID(),true)));
                assertEquals(99,JSON.readTree(call(first,"GET","/view-history",null,null,false).body()).get("total").asLong());
                assertEquals(400,call(first,"GET","/library?size=101",null,null,false).statusCode());
                var search=JSON.readTree(call(first,"GET","/library?q=Library&size=1&sort=title",null,null,false).body());assertTrue(search.get("total").asLong()>=1);assertEquals(1,search.get("items").size());
                f.jdbc.update("UPDATE users SET status='LOCKED' WHERE id=?",a);assertEquals(401,call(first,"GET","/library",null,null,false).statusCode());
            }
        }
    }
}
