package com.example.epub.core.model.identity;
import java.time.Instant;
import java.util.UUID;
/** Internal session row; the cookie ID must never be returned by an HTTP endpoint. */
public record StoredSession(UUID id,String sessionId,Instant createdAt,Instant lastAccessAt) {
    @Override public String toString() { return "StoredSession[redacted]"; }
}
