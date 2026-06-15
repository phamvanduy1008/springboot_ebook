package com.example.epub.core.service.catalog;

import com.example.epub.core.model.catalog.*;
import com.example.epub.core.repository.catalog.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

public class RecommendationService {
    public record Suggestion(BookView book,String reasonCode,String reasonText) { }
    private final RecommendationRepository repository;
    private final CatalogRepository catalog;
    private final Clock clock;
    private final TransactionTemplate reads;
    public RecommendationService(RecommendationRepository repository,CatalogRepository catalog,Clock clock,PlatformTransactionManager manager) {
        this.repository=repository;this.catalog=catalog;this.clock=clock;
        reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public List<Suggestion> recommend(UUID user,int limit) {
        if(limit<1||limit>12)throw CatalogFailure.invalid("limit","Use limit 1..12");
        return reads.execute(tx->recommendWithinSnapshot(user,limit));
    }
    private List<Suggestion> recommendWithinSnapshot(UUID user,int limit) {
        Instant now=clock.instant();
        var history=repository.history(user);
        var source=catalog.views(new ArrayList<>(catalog.publicByIds(history.stream().map(RecommendationRepository.History::id).toList()).values()),false)
            .stream().collect(Collectors.toMap(v->v.book().id(),v->v));
        List<RecommendationScorer.Signal> signals=new ArrayList<>();
        for(var h:history) {
            var view=source.get(h.id());if(view==null)continue;
            List<RecommendationScorer.Signal> options=new ArrayList<>();var item=item(view,0);
            if(h.favorite()&&h.likedAt()!=null)options.add(new RecommendationScorer.Signal(item,5,h.likedAt()));
            if(h.savedAt()!=null&&Set.of("reading","want").contains(h.shelf()==null?"":h.shelf()))options.add(new RecommendationScorer.Signal(item,"reading".equals(h.shelf())?4:3,h.savedAt()));
            if(h.viewedAt()!=null)options.add(new RecommendationScorer.Signal(item,1,h.viewedAt()));
            options.stream().max(Comparator.comparingDouble(s->s.weight()*RecommendationScorer.decay(s.at(),now))).ifPresent(signals::add);
        }
        var profile=RecommendationScorer.profile(signals,now);
        var candidates=repository.candidates(user,now,profile.categories().keySet(),profile.authors().keySet(),profile.tags().keySet());
        var views=catalog.views(new ArrayList<>(catalog.publicByIds(new ArrayList<>(candidates.keySet())).values()),false);
        var byId=views.stream().collect(Collectors.toMap(v->v.book().id(),v->v));
        var picks=RecommendationScorer.rank(views.stream().map(v->item(v,candidates.get(v.book().id()))).toList(),profile,now,limit);
        return picks.stream().map(p->{var view=byId.get(p.id());String reason=switch(p.reasonCode()) {
            case "SAME_CATEGORY"->"Vì bạn quan tâm "+view.categories().stream().filter(c->c.id().equals(p.categoryId())).map(Category::name).findFirst().orElse("thể loại này");
            case "SAME_AUTHOR"->"Từ tác giả bạn quan tâm";
            case "SAME_TAG"->"Có chủ đề bạn quan tâm";
            case "POPULAR"->"Được quan tâm gần đây";
            case "DISCOVERY"->"Khám phá thể loại mới";
            default->"Mới trong thư viện";
        };return new Suggestion(view,p.reasonCode(),reason);}).toList();
    }
    private static RecommendationScorer.Item item(BookView v,double popularity) {
        return new RecommendationScorer.Item(v.book().id(),v.categories().stream().map(Category::id).collect(Collectors.toSet()),
            v.tags().stream().map(RecommendationScorer::tag).filter(t->!t.isEmpty()).collect(Collectors.toSet()),
            v.authors().stream().map(Author::id).collect(Collectors.toSet()),v.book().createdAt(),popularity);
    }
}
