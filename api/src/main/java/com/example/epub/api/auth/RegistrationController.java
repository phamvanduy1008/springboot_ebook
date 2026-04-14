package com.example.epub.api.auth;

import com.example.epub.core.service.identity.AccountService;

import com.example.epub.core.service.identity.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class RegistrationController {
    private final Registration registration;
    private final AccountService accounts;
    private final PasswordEncoder passwords;
    public RegistrationController(Registration registration,AccountService accounts,PasswordEncoder passwords) {
        this.registration=registration;this.accounts=accounts;this.passwords=passwords;
    }
    public record Register(@NotBlank @Email @Size(max=320) String email,@NotBlank @Size(max=60) String displayName,
            @NotBlank @Size(min=12,max=72) String password) {
        @Override public String toString() {return "Register[redacted]";}
    }
    public record EmailInput(@NotBlank @Email @Size(max=320) String email) {
        @Override public String toString() {return "EmailInput[redacted]";}
    }
    public record TokenInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token) {
        @Override public String toString() {return "TokenInput[redacted]";}
    }
    static String normalize(String value) {return value.trim().toLowerCase(Locale.ROOT);}
    void throttle(HttpServletRequest request,String email,String operation) {
        if(!accounts.allowAttempt(AuthController.hash(operation+"-ip:"+request.getRemoteAddr()),20,Duration.ofMinutes(5))
                || !accounts.allowAttempt(AuthController.hash(operation+"-email:"+email),5,Duration.ofMinutes(5)))throw new AuthFailure(429,"RATE_LIMITED");
    }
    static void validatePassword(String value) {
        if(!PasswordPolicy.valid(value))throw new AuthFailure(400,"INVALID_REQUEST",Map.of("password","Use at least 12 characters and at most 72 UTF-8 bytes."));
    }
    static ResponseEntity<?> accepted() {
        return ResponseEntity.accepted().header("Cache-Control","no-store").body(Map.of("message","If this request is eligible, an email will be sent."));
    }
    @PostMapping("/register") public ResponseEntity<?> register(@Valid @RequestBody Register input,HttpServletRequest request) {
        String email=normalize(input.email());throttle(request,email,"register");validatePassword(input.password());
        registration.register(email,input.displayName().strip(),passwords.encode(input.password()));return accepted();
    }
    @PostMapping("/resend-verification") public ResponseEntity<?> resend(@Valid @RequestBody EmailInput input,HttpServletRequest request) {
        String email=normalize(input.email());throttle(request,email,"resend");registration.resend(email);return accepted();
    }
    @PostMapping("/verify-email") public ResponseEntity<?> verify(@Valid @RequestBody TokenInput input,HttpServletRequest request) {
        if(!accounts.allowAttempt(AuthController.hash("verify-ip:"+request.getRemoteAddr()),30,Duration.ofMinutes(5)))throw new AuthFailure(429,"RATE_LIMITED");
        registration.verify(input.token());return ResponseEntity.noContent().header("Cache-Control","no-store").build();
    }
}
