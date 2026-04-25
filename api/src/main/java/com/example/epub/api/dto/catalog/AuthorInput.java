package com.example.epub.api.dto.catalog;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
public record AuthorInput(@NotBlank @Size(max=160) String name,String slug,@Size(max=4000) String biography,Long revision) {
    @JsonAnySetter public void reject(String key,Object value) {throw new IllegalArgumentException("Unknown field");}
}
