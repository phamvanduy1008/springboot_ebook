package com.example.epub.api.dto.catalog;
import com.example.epub.core.model.catalog.*;
import com.example.epub.api.dto.catalog.CatalogResponses.*;
import java.time.Instant;
import java.util.*;
public final class PublicCatalogResponses {
    private PublicCatalogResponses() { }
    public record BookResponse(UUID id,String title,String authorDisplay,String slug,String summary,String description,
        String language,boolean isTranslation,Integer publicationYear,String serialStatus,Instant updatedAt,String coverUrl,boolean readerAvailable,
        List<CategoryResponse> categories,List<AuthorResponse> authors,List<String> tags) { }
    public record HomeResponse(List<BookResponse> featured,List<BookResponse> trending,List<BookResponse> recent,List<BookResponse> suggestions,
        List<AuthorResponse> authors,List<CategoryResponse> categories,long bookCount,long categoryCount) { }
    public record FiltersResponse(List<Integer> years,List<String> groups) { }
    public static BookResponse book(BookView view) {var b=view.book();return new BookResponse(b.id(),b.title(),b.authorDisplay(),b.slug(),b.summary(),b.description(),b.language(),b.isTranslation(),b.publicationYear(),b.serialStatus(),b.updatedAt(),null,false,view.categories().stream().map(CatalogResponses::category).toList(),view.authors().stream().map(CatalogResponses::author).toList(),view.tags());}
    public static HomeResponse home(CatalogHome value) {return new HomeResponse(value.featured().stream().map(PublicCatalogResponses::book).toList(),value.trending().stream().map(PublicCatalogResponses::book).toList(),value.recent().stream().map(PublicCatalogResponses::book).toList(),value.suggestions().stream().map(PublicCatalogResponses::book).toList(),value.authors().stream().map(CatalogResponses::author).toList(),value.categories().stream().map(CatalogResponses::category).toList(),value.bookCount(),value.categoryCount());}
}
