package com.example.epub.core.repository.catalog;
import com.example.epub.core.model.catalog.*;
import com.example.epub.core.model.catalog.RankingModels.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
public class MetricsRepository {
 private final JdbcTemplate jdbc;
 public MetricsRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
 public byte[] key() {return jdbc.queryForObject("SELECT key_material FROM catalog_metric_config WHERE singleton",byte[].class);}
 public boolean view(UUID book,String viewer,Instant at) {
  return jdbc.update("INSERT INTO book_interactions(book_id,kind,viewer_hash,occurred_at) SELECT b.id,'VIEW',?,? FROM books b WHERE b.id=? AND "+CatalogRepository.PUBLIC+" ON CONFLICT DO NOTHING",viewer,Timestamp.from(at),book)>0;
 }
 public Totals totals(UUID id,MetricWindow window) {
  return jdbc.queryForObject("SELECT count(*) FILTER(WHERE kind='VIEW') AS views,count(*) FILTER(WHERE kind='READ') AS reads FROM book_interactions WHERE book_id=? AND occurred_at>=? AND occurred_at<?",(rs,n)->new Totals(rs.getLong("views"),rs.getLong("reads"),0,null,0),id,Timestamp.from(window.from()),Timestamp.from(window.to()));
 }
 private String aggregate(MetricWindow window,List<Object> args,UUID category,String q) {
  args.add(Timestamp.from(window.from()));args.add(Timestamp.from(window.to()));
  String where=CatalogRepository.PUBLIC;
  if(category!=null){where+=" AND EXISTS(SELECT 1 FROM book_categories bc WHERE bc.book_id=b.id AND bc.category_id=?)";args.add(category);}
  if(q!=null&&!q.isBlank()){where+=" AND position(catalog_fold_v1(?) in b.search_text)>0";args.add(q);}
  return "WITH events AS (SELECT book_id,count(*) FILTER(WHERE kind='VIEW') AS views,count(*) FILTER(WHERE kind='READ') AS reads FROM book_interactions WHERE occurred_at>=? AND occurred_at<? GROUP BY book_id), eligible AS (SELECT b.id,coalesce(e.views,0) AS views,coalesce(e.reads,0) AS reads FROM books b LEFT JOIN events e ON e.book_id=b.id WHERE "+where+") ";
 }
 public record Row(UUID id,long views,long reads) { }
 public CatalogPage<Row> books(MetricWindow window,String metric,UUID category,String q,int page,int size) {
  var args=new ArrayList<Object>();String cte=aggregate(window,args,category,q);
  if(metric.equals("rating")||metric.equals("likes"))return new CatalogPage<>(List.of(),page,size,0);
  long total=jdbc.queryForObject(cte+"SELECT count(*) FROM eligible",Long.class,args.toArray());args.add(size);args.add((long)page*size);
  return new CatalogPage<>(jdbc.query(cte+"SELECT * FROM eligible ORDER BY "+(metric.equals("views")?"views":"reads")+" DESC,id ASC LIMIT ? OFFSET ?",(rs,n)->new Row(rs.getObject("id",UUID.class),rs.getLong("views"),rs.getLong("reads")),args.toArray()),page,size,total);
 }
 public CatalogPage<AuthorRank> authors(MetricWindow window,String metric,UUID category,int page,int size) {
  if(metric.equals("rating")||metric.equals("likes"))return new CatalogPage<>(List.of(),page,size,0);
  var args=new ArrayList<Object>();String cte=aggregate(window,args,category,null)+", credited AS (SELECT a.id,a.name,a.slug,count(*) AS book_count,sum(e.views) AS views,sum(e.reads) AS reads FROM authors a JOIN book_authors ba ON ba.author_id=a.id JOIN eligible e ON e.id=ba.book_id GROUP BY a.id) ";
  long total=jdbc.queryForObject(cte+"SELECT count(*) FROM credited",Long.class,args.toArray());args.add(size);args.add((long)page*size);
  var rows=jdbc.query(cte+"SELECT * FROM credited ORDER BY "+(metric.equals("views")?"views":"reads")+" DESC,id ASC LIMIT ? OFFSET ?",(rs,n)->new AuthorRank(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("slug"),rs.getLong("book_count"),(long)page*size+n+1,new Totals(rs.getLong("views"),rs.getLong("reads"),0,null,0)),args.toArray());
  return new CatalogPage<>(rows,page,size,total);
 }
}
