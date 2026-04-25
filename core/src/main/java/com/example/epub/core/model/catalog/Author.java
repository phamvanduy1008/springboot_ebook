package com.example.epub.core.model.catalog;
import java.util.UUID;
public record Author(UUID id,String name,String slug,String biography,long revision,long bookCount) { }
