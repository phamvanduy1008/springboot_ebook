package com.example.epub.api.catalog;

import com.example.epub.api.ApiApplication;
import com.example.epub.core.repository.catalog.RecommendationRepository;
import com.example.epub.core.service.catalog.RecommendationService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.example.epub.core.repository.catalog.CatalogRepository;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.epub.api.catalog.CatalogTestSupport.*;

@EnabledIfEnvironmentVariable(named="EPUB_IDENTITY_DB_TEST",matches="true")
class RecommendationIT {
    @Test void publicPolicySessionIsolationRemovalsAndLimitsAreEnforced() throws Exception {
        try(var context=new SpringApplicationBuilder(ApiApplication.class).run("--spring.profiles.active=local","--server.port=0","--app.local-seed.enabled=false","--logging.level.root=WARN","--debug=false");
            var f=new CatalogTestSupport(context.getBean(JdbcTemplate.class));
            var guest=new Browser(((WebServerApplicationContext)context).getWebServer().getPort());
            var a=new Browser(guest.port);var b=new Browser(guest.port)) {
            assertEquals(0,JSON.readTree(guest.call("GET","/api/v1/recommendations",null,false).body()).get("items").size());
            UUID userA=f.user("USER"),userB=f.user("USER"),privateBook=f.privateBook(userA);
            UUID seed=f.publicDraft("Seed","Author",2026),related=f.publicDraft("Related","Author",2026),hidden=f.publicDraft("Hidden","Author",2026),draft=f.publicDraft("Draft","Author",2026),other=f.publicDraft("Other","Author",2026);
            for(UUID id:List.of(seed,related,hidden,other))f.publish(id);
            f.jdbc.update("UPDATE books SET status='HIDDEN' WHERE id=?",hidden);
            UUID category=UUID.randomUUID();f.categories.add(category);f.jdbc.update("INSERT INTO categories(id,name,slug) VALUES (?,'Tiên Hiệp',?)",category,"rec-"+category);
            for(UUID id:List.of(seed,related,privateBook))f.jdbc.update("INSERT INTO book_categories(book_id,category_id) VALUES (?,?)",id,category);
            Instant now=Instant.parse("2026-10-19T00:00:00Z");
            for(UUID id:f.books)f.jdbc.update("UPDATE books SET created_at=? WHERE id=?",java.sql.Timestamp.from(now.minus(Duration.ofDays(10))),id);
            f.jdbc.update("INSERT INTO personal_book_state(user_id,book_id,favorite,liked_at) VALUES (?,?,true,?)",userA,seed,java.sql.Timestamp.from(now));
            f.jdbc.update("INSERT INTO personal_book_state(user_id,book_id,shelf,saved_at) VALUES (?,?,'finished',?)",userA,other,java.sql.Timestamp.from(now));
            var service=new RecommendationService(context.getBean(RecommendationRepository.class),context.getBean(CatalogRepository.class),Clock.fixed(now,ZoneOffset.UTC),context.getBean(PlatformTransactionManager.class));
            var personalized=service.recommend(userA,6);
            assertEquals(1,personalized.size());assertEquals(related,personalized.getFirst().book().book().id());assertEquals("SAME_CATEGORY",personalized.getFirst().reasonCode());
            assertEquals(3,service.recommend(userB,6).size());
            a.login(userA);b.login(userB);
            var response=a.call("GET","/api/v1/recommendations?limit=6&userId="+userB,null,false);
            assertEquals(200,response.statusCode(),response.body());assertEquals("private, no-store",response.headers().firstValue("Cache-Control").orElse(""));
            var items=JSON.readTree(response.body()).get("items");assertEquals(1,items.size());assertEquals(related.toString(),items.get(0).get("book").get("id").asText());
            assertFalse(response.body().contains(userA.toString()));assertFalse(response.body().contains("score"));
            assertEquals(3,JSON.readTree(b.call("GET","/api/v1/recommendations",null,false).body()).get("items").size());
            for(String value:List.of("0","13","abc","-1"))assertEquals(400,guest.call("GET","/api/v1/recommendations?limit="+value,null,false).statusCode());
            f.jdbc.update("UPDATE personal_book_state SET favorite=false,liked_at=NULL WHERE user_id=?",userA);
            assertTrue(service.recommend(userA,6).stream().noneMatch(s->s.reasonCode().equals("SAME_CATEGORY")));
            f.jdbc.update("INSERT INTO personal_book_state(user_id,book_id,viewed_at) VALUES (?,?,?)",userA,privateBook,java.sql.Timestamp.from(now));
            assertTrue(service.recommend(userA,6).stream().noneMatch(s->s.reasonCode().equals("SAME_CATEGORY")));
            f.jdbc.update("UPDATE personal_book_state SET viewed_at=? WHERE user_id=? AND book_id=?",java.sql.Timestamp.from(now),userA,seed);
            assertTrue(service.recommend(userA,6).stream().anyMatch(s->s.reasonCode().equals("SAME_CATEGORY")));
            f.jdbc.update("UPDATE personal_book_state SET viewed_at=NULL WHERE user_id=?",userA);
            assertTrue(service.recommend(userA,6).stream().noneMatch(s->s.reasonCode().equals("SAME_CATEGORY")));
            f.jdbc.update("INSERT INTO book_interactions(book_id,kind,viewer_hash,occurred_at) VALUES (?,'VIEW',?,?)",related,"a".repeat(64),java.sql.Timestamp.from(now));
            assertEquals("POPULAR",service.recommend(null,6).getFirst().reasonCode());
            f.jdbc.update("UPDATE book_interactions SET occurred_at=? WHERE book_id=?",java.sql.Timestamp.from(now.minus(Duration.ofDays(31))),related);
            assertTrue(service.recommend(null,6).stream().noneMatch(s->s.reasonCode().equals("POPULAR")));
            // Bounded sources must retain older matching books even when newer books fill the recent pool.
            for(int i=0;i<105;i++){UUID id=f.publicDraft("Recent "+i,"Author",2026);f.jdbc.update("UPDATE books SET catalog_listed=true WHERE id=?",id);}
            var candidates=context.getBean(RecommendationRepository.class).candidates(userA,now,Set.of(category),Set.of(),Set.of());
            assertTrue(candidates.containsKey(related));assertTrue(candidates.size()<=200);
            f.jdbc.update("INSERT INTO book_tags(book_id,tag) VALUES (?,' TU   LUYỆN ')",related);
            assertTrue(context.getBean(RecommendationRepository.class).candidates(userA,now,Set.of(),Set.of(),Set.of("tu luyện")).containsKey(related));
            f.jdbc.update("UPDATE personal_book_state SET shelf='want',saved_at=? WHERE user_id=? AND book_id=?",java.sql.Timestamp.from(now.minus(Duration.ofDays(100))),userA,seed);
            for(UUID id:f.books.subList(f.books.size()-105,f.books.size()))f.jdbc.update("INSERT INTO personal_book_state(user_id,book_id,shelf,saved_at) VALUES (?,?,'want',?)",userA,id,java.sql.Timestamp.from(now));
            assertEquals(100,context.getBean(RecommendationRepository.class).history(userA).size());
            assertFalse(context.getBean(RecommendationRepository.class).candidates(userA,now,Set.of(category),Set.of(),Set.of()).containsKey(seed));
        }
    }
}
