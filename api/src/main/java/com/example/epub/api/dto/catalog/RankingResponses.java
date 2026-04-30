package com.example.epub.api.dto.catalog;
import com.example.epub.core.model.catalog.RankingModels.Totals;
import com.example.epub.core.model.catalog.MetricWindow;
import java.util.List;
public final class RankingResponses {
 private RankingResponses() { }
 public record RankedBook(PublicCatalogResponses.BookResponse book,long rank,Totals metrics) { }
 public record RankPage<T>(List<T> items,int page,int size,long total,String metric,MetricWindow window) { }
}
