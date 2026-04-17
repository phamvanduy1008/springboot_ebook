package com.example.epub.api.controller.auth;

import com.example.epub.api.dto.auth.EmailRequest;
import com.example.epub.api.dto.auth.RegistrationRequest;
import com.example.epub.api.dto.auth.TokenRequest;
import com.example.epub.core.service.identity.Registration;
import com.example.epub.api.security.AuthRequestChecks;
import com.example.epub.api.dto.auth.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class RegistrationController {
    private final Registration registration;
    private final AuthRequestChecks checks;
    private final PasswordEncoder passwords;
    public RegistrationController(Registration registration,AuthRequestChecks checks,PasswordEncoder passwords) { this.registration=registration;this.checks=checks;this.passwords=passwords; }
    @PostMapping("/register") public ResponseEntity<?> register(@Valid @RequestBody RegistrationRequest input,HttpServletRequest request) {
        String email=AuthRequestChecks.normalize(input.email());checks.throttle(request.getRemoteAddr(),email,"register");AuthRequestChecks.validatePassword(input.password());
        registration.register(email,input.displayName().strip(),passwords.encode(input.password()));return AuthResponses.accepted();
    }
    @PostMapping("/resend-verification") public ResponseEntity<?> resend(@Valid @RequestBody EmailRequest input,HttpServletRequest request) {
        String email=AuthRequestChecks.normalize(input.email());checks.throttle(request.getRemoteAddr(),email,"resend");registration.resend(email);return AuthResponses.accepted();
    }
    @PostMapping("/verify-email") public ResponseEntity<?> verify(@Valid @RequestBody TokenRequest input,HttpServletRequest request) {
        checks.limitIp(request.getRemoteAddr(),"verify");registration.verify(input.token());return ResponseEntity.noContent().header("Cache-Control","no-store").build();
    }
}
