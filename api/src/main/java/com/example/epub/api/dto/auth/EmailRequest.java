package com.example.epub.api.dto.auth;

import jakarta.validation.constraints.*;

public record EmailRequest(@NotBlank @Email @Size(max=320) String email) {
    @Override public String toString() {return "EmailRequest[redacted]";}
}
