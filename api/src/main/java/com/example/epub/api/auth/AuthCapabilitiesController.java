package com.example.epub.api.auth;

import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthCapabilitiesController {
    private final Environment env;
    public AuthCapabilitiesController(Environment env) {this.env=env;}
    @GetMapping("/api/v1/auth/capabilities") public Map<String,Boolean> capabilities(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        return Map.of("password",true,"google",env.getProperty("app.google.enabled",Boolean.class,false));
    }
}
