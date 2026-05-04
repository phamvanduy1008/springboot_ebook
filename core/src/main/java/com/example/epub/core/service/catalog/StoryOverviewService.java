package com.example.epub.core.service.catalog;
import com.example.epub.core.model.catalog.*;
import com.example.epub.core.model.catalog.EditorialModels.*;
import com.example.epub.core.model.catalog.EditorialModels.Character;
import com.example.epub.core.repository.catalog.*;
import java.time.Clock;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
public class StoryOverviewService {
 private final CatalogRepository catalog;private final EditorialRepository editorial;private final MetricsRepository metrics;private final Clock clock;private final TransactionTemplate reads;
 public StoryOverviewService(CatalogRepository catalog,EditorialRepository editorial,MetricsRepository metrics,Clock clock,PlatformTransactionManager manager) {this.catalog=catalog;this.editorial=editorial;this.metrics=metrics;this.clock=clock;reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);}
 public Overview overview(UUID id) {
  return reads.execute(tx->{var book=catalog.find(id,false).orElseThrow(()->new CatalogFailure(CatalogFailure.Reason.NOT_FOUND));var window=MetricWindow.of("month",clock);var ids=editorial.related(id);var byId=catalog.publicByIds(ids);
   return new Overview(catalog.views(List.of(book),false).getFirst(),book.description(),editorial.quote(id),editorial.chapterCount(id),editorial.characters(id),metrics.totals(id,window),List.of(0L,0L,0L,0L,0L),metrics.viewRank(id,window),window,catalog.views(ids.stream().map(byId::get).toList(),false));
  });
 }
}
