package com.example.epub.core.model.catalog;
import java.util.*;
public record BookDraft(String title,String authorDisplay,String slug,String summary,String description,String language,
        boolean isTranslation,Integer publicationYear,String serialStatus,List<UUID> authorIds,List<UUID> categoryIds,List<String> tags,String rightsNote) { }
