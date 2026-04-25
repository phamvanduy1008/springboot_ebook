package com.example.epub.core.model.catalog;
import java.util.List;
public record CatalogPage<T>(List<T> items,int page,int size,long total) {
    public CatalogPage {items=List.copyOf(items);}
}
