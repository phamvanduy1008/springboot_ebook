package com.example.epub.core.model.catalog;
import java.util.*;
public final class EditorialModels {
 private EditorialModels() { }
 public record Character(UUID id,String name,String role,String description,String initials,String color,int position,long revision) { }
 public record Editorial(String quote,List<Character> characters,long revision) { }
 public record CharacterDraft(UUID id,String name,String role,String description,String initials,String color) { }
 public record Overview(BookView book,String introduction,String quote,Integer chapterCount,List<Character> characters,RankingModels.Totals metrics,List<Long> distribution,Long viewRank,MetricWindow window,List<BookView> related) { }
}
