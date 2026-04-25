package com.example.epub.api.dto.catalog;
import com.example.epub.core.model.catalog.BookDraft;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.util.*;
public record BookInput(@NotBlank @Size(max=300) String title,@NotBlank @Size(max=500) String authorDisplay,String slug,
        @Size(max=2000) String summary,@Size(max=20000) String description,@Size(max=35) String language,Boolean isTranslation,Integer publicationYear,
        String serialStatus,@Size(max=30) List<UUID> authorIds,@Size(max=30) List<UUID> categoryIds,@Size(max=30) List<String> tags,@Size(max=4000) String rightsNote,Long revision) {
    @JsonAnySetter public void reject(String key,Object value) {throw new IllegalArgumentException("Unknown field");}
    public BookDraft draft() {return new BookDraft(title,authorDisplay,slug,summary,description,language,Boolean.TRUE.equals(isTranslation),publicationYear,serialStatus,authorIds,categoryIds,tags,rightsNote);}
}
