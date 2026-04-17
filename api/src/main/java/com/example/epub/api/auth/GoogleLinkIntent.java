package com.example.epub.api.auth;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Deserialization bridge for sessions saved before the security package move. */
@Deprecated
public record GoogleLinkIntent(UUID userId,Instant credentialEpoch,Instant issuedAt) implements Serializable {
    public static final String PENDING="google.pendingLink";
    public static final String ATTRIBUTE="app.linkIntent";
    public boolean valid() {return issuedAt.isAfter(Instant.now().minusSeconds(300));}
    @java.io.Serial private Object readResolve() { return new com.example.epub.api.security.GoogleLinkIntent(userId,credentialEpoch,issuedAt); }
}
