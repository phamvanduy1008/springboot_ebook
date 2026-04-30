package com.example.epub.api.controller.catalog;
import com.example.epub.api.dto.catalog.PublicCatalogResponses;
import com.example.epub.api.dto.catalog.RankingResponses.*;
import com.example.epub.api.security.SessionPrincipal;
import com.example.epub.core.model.catalog.RankingModels.*;
import com.example.epub.core.model.catalog.MetricWindow;
import com.example.epub.core.service.catalog.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1")
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class RankingController {
 private final RankingService rankings;public RankingController(RankingService rankings){this.rankings=rankings;}
 @ModelAttribute public void cache(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
 @GetMapping("/rankings") public RankPage<RankedBook> books(@RequestParam(defaultValue="month")String period,@RequestParam(defaultValue="views")String metric,@RequestParam(required=false)UUID category,@RequestParam(defaultValue="")String q,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size) {
  var result=rankings.books(period,metric,category,q,page,size);return new RankPage<>(result.items().stream().map(v->new RankedBook(PublicCatalogResponses.book(v.book()),v.rank(),v.metrics())).toList(),page,size,result.total(),metric,result.window());
 }
 @GetMapping("/rankings/authors") public RankPage<AuthorRank> authors(@RequestParam(defaultValue="month")String period,@RequestParam(defaultValue="views")String metric,@RequestParam(required=false)UUID category,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size) {var r=rankings.authors(period,metric,category,page,size);return new RankPage<>(r.items(),page,size,r.total(),metric,r.window());}
 @PostMapping("/books/{id}/views") public Map<String,Boolean> view(@PathVariable UUID id,@RequestBody(required=false)Map<String,Object> body,@AuthenticationPrincipal SessionPrincipal principal,HttpServletRequest request) {
  if(body!=null&&!body.isEmpty())throw CatalogFailure.invalid("body","View has no client-controlled fields");
  return Map.of("recorded",rankings.view(id,new Viewer(principal==null?null:principal.userId(),request.getRemoteAddr(),request.getHeader("User-Agent"))));
 }
}
