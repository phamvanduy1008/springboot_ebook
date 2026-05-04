package com.example.epub.api.dto.catalog;
import com.example.epub.core.model.catalog.EditorialModels.CharacterDraft;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
public final class EditorialInput {
 private EditorialInput() { }
 public record Quote(@NotNull @PositiveOrZero Long revision,String quote) { @JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unknown field: "+key);} }
 public record Character(UUID id,String name,String role,String description,String initials,String color) {
  @JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unknown field: "+key);}
  public CharacterDraft draft(){return new CharacterDraft(id,name,role,description,initials,color);}
 }
 public record Characters(@NotNull @PositiveOrZero Long revision,@NotNull @Size(max=30) List<@NotNull @Valid Character> characters) { @JsonAnySetter public void reject(String key,Object value){throw new IllegalArgumentException("Unknown field: "+key);} }
}
