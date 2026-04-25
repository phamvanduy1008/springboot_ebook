package com.example.epub.core.model.catalog;
import java.time.Instant;
import java.util.UUID;
public record BookVersion(UUID id,int versionNo,String state,String rightsNote,Instant createdAt) { }
