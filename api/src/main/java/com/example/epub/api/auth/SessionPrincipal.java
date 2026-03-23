package com.example.epub.api.auth;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

/** Only application identity belongs in the persistent security context. */
public record SessionPrincipal(UUID userId,java.time.Instant credentialEpoch) implements Principal, Serializable {
    @Override public String getName() { return userId.toString(); }
}
