package com.example.epub.api.dto.auth;

import jakarta.validation.constraints.*;

public record TokenRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token) {
    @Override public String toString() {return "TokenRequest[redacted]";}
}
