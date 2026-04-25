package com.example.epub.core.model.catalog;
import java.util.List;
public record BookView(CatalogBook book,List<Category> categories,List<Author> authors,List<String> tags,List<BookVersion> versions) {
    public BookView {categories=List.copyOf(categories);authors=List.copyOf(authors);tags=List.copyOf(tags);versions=List.copyOf(versions);}
}
