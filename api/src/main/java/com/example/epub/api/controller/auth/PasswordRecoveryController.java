package com.example.epub.api.controller.auth;

import com.example.epub.api.dto.auth.EmailRequest;
import com.example.epub.api.dto.auth.ResetPasswordRequest;
import com.example.epub.core.service.identity.*;
import com.example.epub.api.security.AuthRequestChecks;
import com.example.epub.api.dto.auth.*;
import com.example.epub.api.filter.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class PasswordRecoveryController {
    private final PasswordRecovery recovery;
    private final AuthRequestChecks checks;
    private final PasswordEncoder passwords;
    public PasswordRecoveryController(PasswordRecovery recovery,AuthRequestChecks checks,PasswordEncoder passwords) { this.recovery=recovery;this.checks=checks;this.passwords=passwords; }
    @PostMapping("/forgot-password") public ResponseEntity<?> forgot(@Valid @RequestBody EmailRequest input,HttpServletRequest request) {
        String email=AuthRequestChecks.normalize(input.email());checks.throttle(request.getRemoteAddr(),email,"forgot");
        try { recovery.forgot(email); } catch(Registration.MailUnavailable unavailable) { /* Delivery failure also receives the neutral response. */ }
        return AuthResponses.accepted();
    }
    @PostMapping("/reset-password") public ResponseEntity<?> reset(@Valid @RequestBody ResetPasswordRequest input,HttpServletRequest request) {
        checks.limitIp(request.getRemoteAddr(),"reset");AuthRequestChecks.validatePassword(input.password());
        recovery.reset(input.token(),passwords.encode(input.password()),(String)request.getAttribute(RequestIdFilter.ATTRIBUTE));
        return ResponseEntity.noContent().header("Cache-Control","no-store").build();
    }
}
