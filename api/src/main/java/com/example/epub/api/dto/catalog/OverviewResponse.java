package com.example.epub.api.dto.catalog;
import com.example.epub.core.model.catalog.*;
import com.example.epub.core.model.catalog.EditorialModels.*;
import com.example.epub.core.model.catalog.EditorialModels.Character;
import java.util.List;
public record OverviewResponse(PublicCatalogResponses.BookResponse book,String introduction,String quote,Integer chapterCount,List<Character> characters,RankingModels.Totals metrics,List<Long> distribution,Long viewRank,MetricWindow window,List<PublicCatalogResponses.BookResponse> related) {
 public static OverviewResponse from(Overview v) {return new OverviewResponse(PublicCatalogResponses.book(v.book()),v.introduction(),v.quote(),v.chapterCount(),v.characters(),v.metrics(),v.distribution(),v.viewRank(),v.window(),v.related().stream().map(PublicCatalogResponses::book).toList());}
}
