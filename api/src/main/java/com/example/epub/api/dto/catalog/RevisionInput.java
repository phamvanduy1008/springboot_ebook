package com.example.epub.api.dto.catalog;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
public record RevisionInput(@NotNull @PositiveOrZero Long revision,@Size(max=4000) String rightsNote) {
    @JsonAnySetter public void reject(String key,Object value) {throw new IllegalArgumentException("Unknown field");}
}
