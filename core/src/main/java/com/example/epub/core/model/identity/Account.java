package com.example.epub.core.model.identity;

import java.time.Instant;
import java.util.UUID;

/** Internal snapshot. Never serialize this object as an HTTP response. */
public record Account(UUID id, String email, String passwordHash, String displayName, String bio,
        String role, String status, Instant emailVerifiedAt, Instant passwordChangedAt, long revision) {
    public boolean active() { return "ACTIVE".equals(status); }
    @Override public String toString() { return "Account[id="+id+", credentials redacted]"; }
}
