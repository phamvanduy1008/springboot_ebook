package com.example.epub.api.controller.auth;
import java.util.Map;
import org.springframework.http.ResponseEntity;
final class AuthResponses {
    private AuthResponses() { }
    static ResponseEntity<?> accepted() {
        return ResponseEntity.accepted().header("Cache-Control","no-store").body(Map.of("message","If this request is eligible, an email will be sent."));
    }
}
