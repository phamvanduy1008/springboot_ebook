package com.example.epub.api.controller;

import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ApiHealthController {
    private final HealthEndpoint health;
    public ApiHealthController(HealthEndpoint health) {this.health=health;}
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        String status=health.health().getStatus().getCode();
        return ResponseEntity.status("UP".equals(status)?200:503).body(new HealthResponse(status));
    }
    public record HealthResponse(String status) { }
}
