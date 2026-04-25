package com.example.epub.core.model.catalog;
import java.util.UUID;
public record Category(UUID id,String name,String slug,String groupName,String description,String color,int sortOrder,long revision,long bookCount) { }
