package com.example.epub.core.model.catalog;
import java.util.List;
public record CatalogHome(List<BookView> featured,List<BookView> trending,List<BookView> recent,List<BookView> suggestions,
        List<Author> authors,List<Category> categories,long bookCount,long categoryCount) { }
