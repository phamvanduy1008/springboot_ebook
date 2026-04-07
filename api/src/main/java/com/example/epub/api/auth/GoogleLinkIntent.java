package com.example.epub.api.auth;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record GoogleLinkIntent(UUID userId,Instant credentialEpoch,Instant issuedAt) implements Serializable {
    public static final String PENDING="google.pendingLink";
    public static final String ATTRIBUTE="app.linkIntent";
    public boolean valid() {return issuedAt.isAfter(Instant.now().minusSeconds(300));}
}
