package com.example.epub.core.service.catalog;
import com.example.epub.core.model.catalog.*;
import com.example.epub.core.model.catalog.RankingModels.*;
import com.example.epub.core.repository.catalog.*;
import com.example.epub.core.service.identity.AccountService;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
public class RankingService {
 private final CatalogRepository catalog;private final MetricsRepository metrics;private final AccountService accounts;private final Clock clock;private final TransactionTemplate reads;
 public RankingService(CatalogRepository catalog,MetricsRepository metrics,AccountService accounts,Clock clock,PlatformTransactionManager manager) {
  this.catalog=catalog;this.metrics=metrics;this.accounts=accounts;this.clock=clock;reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
 }
 private MetricWindow window(String period) {try{return MetricWindow.of(period,clock);}catch(IllegalArgumentException e){throw CatalogFailure.invalid("period","Use week, month or year");}}
 private void validate(String metric,int page,int size) {CatalogValidation.query(new CatalogQuery(null,null,null,null,page,size,null),false);if(!Set.of("views","reads","likes","rating").contains(metric))throw CatalogFailure.invalid("metric","Unsupported metric");}
 public Page<BookRank> books(String period,String metric,UUID category,String q,int page,int size) {
  validate(metric,page,size);String search=CatalogValidation.text(q,"q",200,false);var window=window(period);
  return reads.execute(tx->{var rows=metrics.books(window,metric,category,search,page,size);var result=new ArrayList<BookRank>();long rank=(long)page*size;
   var byId=catalog.publicByIds(rows.items().stream().map(MetricsRepository.Row::id).toList());var views=catalog.views(rows.items().stream().map(row->byId.get(row.id())).toList(),false);
   for(int i=0;i<views.size();i++){var row=rows.items().get(i);result.add(new BookRank(views.get(i),++rank,new Totals(row.views(),row.reads(),0,null,0)));}
   return new Page<>(result,page,size,rows.total(),metric,window);
  });
 }
 public Page<AuthorRank> authors(String period,String metric,UUID category,int page,int size) {validate(metric,page,size);var window=window(period);return reads.execute(tx->{var rows=metrics.authors(window,metric,category,page,size);return new Page<>(rows.items(),page,size,rows.total(),metric,window);});}
 public Totals totals(UUID id,String period) {return metrics.totals(id,window(period));}
 public boolean view(UUID id,Viewer viewer) {
  if(catalog.find(id,false).isEmpty())throw new CatalogFailure(CatalogFailure.Reason.NOT_FOUND);
  String address=viewer.address()==null?"unknown":viewer.address();String agent=viewer.userAgent()==null?"":viewer.userAgent().substring(0,Math.min(512,viewer.userAgent().length()));
  if(!accounts.allowAttempt(hash("catalog-view:"+address),60,Duration.ofMinutes(1)))throw new CatalogFailure(CatalogFailure.Reason.RATE_LIMITED);
  String identity=viewer.userId()==null?"guest:"+address+":"+agent:"user:"+viewer.userId();
  return metrics.view(id,hash(identity),clock.instant());
 }
 private String hash(String input) {try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(metrics.key(),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));}catch(java.security.GeneralSecurityException e){throw new IllegalStateException("Metric identity hashing unavailable",e);}}
}
