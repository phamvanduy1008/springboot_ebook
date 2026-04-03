package com.example.epub.api.auth;

import com.example.epub.application.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class PasswordRecoveryController {
    private final PasswordRecovery recovery;
    private final AccountStore accounts;
    private final PasswordEncoder passwords;
    private final RegistrationController registration;
    public PasswordRecoveryController(PasswordRecovery recovery,AccountStore accounts,PasswordEncoder passwords,RegistrationController registration) {
        this.recovery=recovery;this.accounts=accounts;this.passwords=passwords;this.registration=registration;
    }
    public record Reset(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,@NotBlank @Size(min=12,max=72) String password) {
        @Override public String toString() {return "Reset[redacted]";}
    }
    @PostMapping("/forgot-password") public ResponseEntity<?> forgot(@Valid @RequestBody RegistrationController.EmailInput input,HttpServletRequest request) {
        String email=RegistrationController.normalize(input.email());registration.throttle(request,email,"forgot");
        try {recovery.forgot(email);}catch(Registration.MailUnavailable unavailable) {
            // The neutral response also applies on delivery failure, without revealing account existence.
        }
        return RegistrationController.accepted();
    }
    @PostMapping("/reset-password") public ResponseEntity<?> reset(@Valid @RequestBody Reset input,HttpServletRequest request) {
        if(!accounts.allowAttempt(AuthController.hash("reset-ip:"+request.getRemoteAddr()),30,Duration.ofMinutes(5)))throw new AuthFailure(429,"RATE_LIMITED");
        RegistrationController.validatePassword(input.password());
        recovery.reset(input.token(),passwords.encode(input.password()),(String)request.getAttribute(com.example.epub.api.RequestIdFilter.ATTRIBUTE));
        return ResponseEntity.noContent().header("Cache-Control","no-store").build();
    }
}
