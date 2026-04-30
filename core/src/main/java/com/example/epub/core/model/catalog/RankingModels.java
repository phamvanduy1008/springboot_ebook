package com.example.epub.core.model.catalog;
import java.util.*;
public final class RankingModels {
 private RankingModels() { }
 public record Totals(long views,long reads,long likes,Double averageRating,long ratingCount) { }
 public record BookRank(BookView book,long rank,Totals metrics) { }
 public record AuthorRank(UUID id,String name,String slug,long bookCount,long rank,Totals metrics) { }
 public record Page<T>(List<T> items,int page,int size,long total,String metric,MetricWindow window) { }
 public record Viewer(UUID userId,String address,String userAgent) { @Override public String toString() {return "Viewer[redacted]";} }
}
