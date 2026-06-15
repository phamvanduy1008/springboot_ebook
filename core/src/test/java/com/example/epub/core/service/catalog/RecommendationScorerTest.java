package com.example.epub.core.service.catalog;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.epub.core.service.catalog.RecommendationScorer.*;

class RecommendationScorerTest {
    final Instant now=Instant.parse("2026-10-19T00:00:00Z");
    final UUID fantasy=new UUID(0,100), horror=new UUID(0,101), author=new UUID(0,200);
    Item item(int id,Set<UUID> cats,Set<String> tags,Set<UUID> authors,int age,double popularity) {
        return new Item(new UUID(0,id),cats,tags,authors,now.minus(Duration.ofDays(age)),popularity);
    }
    @Test void halfLifeAndFutureClockSkewAreBounded() {
        assertEquals(.5,decay(now.minus(Duration.ofDays(30)),now),1e-9);
        assertEquals(1,decay(now.plusSeconds(1),now));
    }
    @Test void profilesUseDecayAndDoNotRewardExtraMetadata() {
        var a=item(1,Set.of(fantasy),Set.of(),Set.of(),0,0);
        var b=item(2,Set.of(horror),Set.of(),Set.of(),0,0);
        var p=profile(List.of(new Signal(a,5,now),new Signal(b,5,now.minus(Duration.ofDays(30)))),now);
        assertEquals(1,p.categories().get(fantasy));assertEquals(.5,p.categories().get(horror));
        var single=item(3,Set.of(fantasy),Set.of(),Set.of(),0,0);
        var noisy=item(4,Set.of(fantasy,horror),Set.of(),Set.of(),0,0);
        assertEquals(single.id(),rank(List.of(noisy,single),p,now,1).getFirst().id());
        assertEquals("tu luyện",tag("  TU   LUYỆN "));
    }
    @Test void personalizedMixIncludesPopularityAndExplorationWithTruthfulReasons() {
        var seed=item(99,Set.of(fantasy),Set.of(),Set.of(),0,0);
        var p=profile(List.of(new Signal(seed,5,now)),now);
        List<Item> items=new ArrayList<>();
        for(int i=1;i<=6;i++)items.add(item(i,Set.of(fantasy),Set.of(),Set.of(),10,0));
        var popular=item(7,Set.of(fantasy),Set.of(),Set.of(),40,500);items.add(popular);
        var explorer=item(8,Set.of(horror),Set.of(),Set.of(),50,0);items.add(explorer);
        var picks=rank(items,p,now,6);
        assertEquals(6,picks.size());assertEquals(6,picks.stream().map(Pick::id).distinct().count());
        assertTrue(picks.stream().anyMatch(v->v.id().equals(explorer.id())&&v.reasonCode().equals("DISCOVERY")));
        assertTrue(picks.stream().anyMatch(v->v.id().equals(popular.id())));
        assertTrue(picks.stream().filter(v->v.reasonCode().equals("SAME_CATEGORY")).allMatch(v->fantasy.equals(v.categoryId())));
    }
    @Test void guestsAreStableAndAuthorCapRelaxesOnlyWhenNeeded() {
        var p=profile(List.of(),now);
        List<Item> items=new ArrayList<>();
        for(int i=1;i<=5;i++)items.add(item(i,Set.of(),Set.of(),Set.of(author),0,0));
        for(int i=6;i<=9;i++)items.add(item(i,Set.of(),Set.of(),Set.of(new UUID(0,200+i)),0,0));
        var first=rank(items,p,now,6);Collections.reverse(items);
        assertEquals(first,rank(items,p,now,6));
        assertEquals(2,first.stream().filter(v->v.id().getLeastSignificantBits()<=5).count());
        assertEquals(5,rank(items.stream().filter(i->i.authors().contains(author)).toList(),p,now,6).size());
        assertTrue(rank(List.of(),p,now,6).isEmpty());
    }
    @Test void tagAndAuthorReasonsAndMissingMetadataAreHandled() {
        var seed=item(99,Set.of(),Set.of("magic"),Set.of(author),0,0);
        var p=profile(List.of(new Signal(seed,5,now)),now);
        assertEquals("SAME_AUTHOR",rank(List.of(item(1,Set.of(),Set.of(),Set.of(author),0,0)),p,now,1).getFirst().reasonCode());
        assertEquals("SAME_TAG",rank(List.of(item(2,Set.of(),Set.of("magic"),Set.of(),0,0)),p,now,1).getFirst().reasonCode());
        assertEquals("NEW",rank(List.of(item(3,Set.of(),Set.of(),Set.of(),0,0)),p,now,1).getFirst().reasonCode());
    }
}
