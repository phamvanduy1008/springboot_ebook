package com.example.epub.api.dto.auth;

import jakarta.validation.constraints.*;

public record RegistrationRequest(@NotBlank @Email @Size(max=320) String email,@NotBlank @Size(max=60) String displayName,
        @NotBlank @Size(min=12,max=72) String password) {
    @Override public String toString() {return "RegistrationRequest[redacted]";}
}
