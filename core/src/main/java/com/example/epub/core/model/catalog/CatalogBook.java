package com.example.epub.core.model.catalog;
import java.time.Instant;
import java.util.UUID;
public record CatalogBook(UUID id,String title,String authorDisplay,String slug,String summary,String description,
        String language,boolean isTranslation,Integer publicationYear,String serialStatus,String status,String visibility,
        long revision,Instant createdAt,Instant updatedAt,UUID publishedVersionId,UUID currentVersionId,String coverKey,int versionNo,String versionState) { }
