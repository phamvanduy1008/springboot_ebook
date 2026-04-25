package com.example.epub.api.dto.catalog;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
public record CategoryInput(@NotBlank @Size(max=100) String name,String slug,@Size(max=40) String groupName,@Size(max=2000) String description,String color,Integer sortOrder,Long revision) {
    @JsonAnySetter public void reject(String key,Object value) {throw new IllegalArgumentException("Unknown field");}
}
