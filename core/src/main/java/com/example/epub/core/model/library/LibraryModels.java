package com.example.epub.core.model.library;

import com.example.epub.core.model.catalog.BookView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LibraryModels {
    private LibraryModels() { }
    public record Actor(UUID id, Instant credentialEpoch) { }
    public record State(UUID bookId, String shelf, boolean favorite, Instant viewedAt, long revision, boolean owned) { }
    public record Entry(BookView book, State state) { }
    public record Counts(long all, long want, long reading, long finished, long owned, long favorites, long history) { }
    public record Page(List<Entry> items, int page, int size, long total, Counts counts) { }
    public record Query(String shelf, String ownership, String q, String sort, int page, int size) { }
    public record Settings(String theme, String readerTheme, int fontSize, double lineHeight, String readerFont, long revision) {
        public static Settings defaults() { return new Settings("light","light",22,1.8,"serif",0); }
    }
}
