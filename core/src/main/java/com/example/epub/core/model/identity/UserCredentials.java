package com.example.epub.core.model.identity;

import java.util.UUID;

/** Internal authentication data; never a REST response. */
public record UserCredentials(UUID id, String email, String passwordHash, String role, String status) {
    @Override public String toString() { return "UserCredentials[id=" + id + ", credentials redacted]"; }
}
