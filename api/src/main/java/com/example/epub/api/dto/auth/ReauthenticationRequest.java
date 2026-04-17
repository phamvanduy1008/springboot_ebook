package com.example.epub.api.dto.auth;

import jakarta.validation.constraints.*;

public record ReauthenticationRequest(@NotBlank @Size(max=72) String currentPassword) {
    @Override public String toString() {return "ReauthenticationRequest[redacted]";}
}
