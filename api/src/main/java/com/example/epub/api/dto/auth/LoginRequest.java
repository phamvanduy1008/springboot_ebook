package com.example.epub.api.dto.auth;

import jakarta.validation.constraints.*;

public record LoginRequest(@NotBlank @Email @Size(max=320) String email,@NotBlank @Size(max=72) String password) {
    @Override public String toString() { return "LoginRequest[redacted]"; }
}
