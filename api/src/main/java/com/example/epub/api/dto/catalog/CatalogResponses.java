package com.example.epub.api.dto.catalog;
import com.example.epub.core.model.catalog.*;
import com.example.epub.api.dto.common.PageResult;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
public final class CatalogResponses {
    private CatalogResponses() { }
    public record CategoryResponse(UUID id,String name,String slug,String groupName,String description,String color,int sortOrder,long revision,long bookCount) { }
    public record AuthorResponse(UUID id,String name,String slug,String biography,long revision,long bookCount) { }
    public record VersionResponse(UUID id,int versionNo,String state,String rightsNote,Instant createdAt) { }
    public record AdminBookResponse(UUID id,String title,String authorDisplay,String slug,String summary,String description,String language,boolean isTranslation,Integer publicationYear,
        String serialStatus,String status,String visibility,long revision,Instant updatedAt,int versionNo,String versionState,List<CategoryResponse> categories,List<AuthorResponse> authors,List<String> tags,List<VersionResponse> versions) { }
    public static CategoryResponse category(Category value) {return new CategoryResponse(value.id(),value.name(),value.slug(),value.groupName(),value.description(),value.color(),value.sortOrder(),value.revision(),value.bookCount());}
    public static AuthorResponse author(Author value) {return new AuthorResponse(value.id(),value.name(),value.slug(),value.biography(),value.revision(),value.bookCount());}
    public static VersionResponse version(BookVersion value) {return new VersionResponse(value.id(),value.versionNo(),value.state(),value.rightsNote(),value.createdAt());}
    public static AdminBookResponse admin(BookView view) {var b=view.book();return new AdminBookResponse(b.id(),b.title(),b.authorDisplay(),b.slug(),b.summary(),b.description(),b.language(),b.isTranslation(),b.publicationYear(),b.serialStatus(),b.status(),b.visibility(),b.revision(),b.updatedAt(),b.versionNo(),b.versionState(),view.categories().stream().map(CatalogResponses::category).toList(),view.authors().stream().map(CatalogResponses::author).toList(),view.tags(),view.versions().stream().map(CatalogResponses::version).toList());}
    public static <T,R> PageResult<R> page(CatalogPage<T> value,Function<T,R> map) {return new PageResult<>(value.items().stream().map(map).toList(),value.page(),value.size(),value.total());}
}
