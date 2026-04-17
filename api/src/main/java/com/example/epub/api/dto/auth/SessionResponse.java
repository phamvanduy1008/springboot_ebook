package com.example.epub.api.dto.auth;
import java.time.Instant;
import java.util.UUID;
public record SessionResponse(UUID id,Instant createdAt,Instant lastAccessAt,boolean current) { }
