package com.example.epub.api.dto.auth;

import jakarta.validation.constraints.*;

public record ResetPasswordRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,@NotBlank @Size(min=12,max=72) String password) {
    @Override public String toString() {return "ResetPasswordRequest[redacted]";}
}
