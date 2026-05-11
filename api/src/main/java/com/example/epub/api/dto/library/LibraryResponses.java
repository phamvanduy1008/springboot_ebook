package com.example.epub.api.dto.library;

import com.example.epub.api.dto.catalog.PublicCatalogResponses;
import com.example.epub.core.model.library.LibraryModels;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LibraryResponses {
    private LibraryResponses() { }
    public record State(UUID bookId,String shelf,boolean favorite,Instant viewedAt,long revision,boolean owned) { }
    public record Entry(PublicCatalogResponses.BookResponse book,String shelf,boolean favorite,Instant viewedAt,long revision,boolean owned) { }
    public record Counts(long all,long want,long reading,long finished,long owned,long favorites,long history) { }
    public record Page(List<Entry> items,int page,int size,long total,Counts counts) { }
    public record Settings(String theme,String readerTheme,int fontSize,double lineHeight,String readerFont,long revision) { }
    public static State state(LibraryModels.State value) {return new State(value.bookId(),value.shelf(),value.favorite(),value.viewedAt(),value.revision(),value.owned());}
    public static Settings settings(LibraryModels.Settings value) {return new Settings(value.theme(),value.readerTheme(),value.fontSize(),value.lineHeight(),value.readerFont(),value.revision());}
    public static Page page(LibraryModels.Page page) {
        var counts=page.counts();return new Page(page.items().stream().map(entry->{var s=entry.state();return new Entry(PublicCatalogResponses.book(entry.book()),s.shelf(),s.favorite(),s.viewedAt(),s.revision(),s.owned());}).toList(),page.page(),page.size(),page.total(),new Counts(counts.all(),counts.want(),counts.reading(),counts.finished(),counts.owned(),counts.favorites(),counts.history()));
    }
}
