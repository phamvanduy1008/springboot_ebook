package com.example.epub.api.auth;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

/** Only application identity belongs in the persistent security context. */
/** Deserialization bridge for sessions saved before the security package move. */
@Deprecated
public record SessionPrincipal(UUID userId,java.time.Instant credentialEpoch) implements Principal, Serializable {
    @Override public String getName() { return userId.toString(); }
    @java.io.Serial private Object readResolve() { return new com.example.epub.api.security.SessionPrincipal(userId,credentialEpoch); }
}
