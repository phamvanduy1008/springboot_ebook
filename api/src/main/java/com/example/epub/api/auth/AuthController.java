package com.example.epub.api.auth;

import com.example.epub.application.identity.AccountStore;
import com.example.epub.domain.identity.Account;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
public class AuthController {
    private final AccountStore accounts;
    private final PasswordEncoder passwords;
    private final SessionLogin sessions;
    private final String dummyHash;
    private final com.example.epub.application.identity.AuditEvents audits;
    public AuthController(AccountStore accounts,PasswordEncoder passwords,SessionLogin sessions,com.example.epub.application.identity.AuditEvents audits) {
        this.accounts=accounts;this.passwords=passwords;this.sessions=sessions;
        this.audits=audits;
        dummyHash=passwords.encode(UUID.randomUUID().toString());
    }
    public record Login(@NotBlank @Email @Size(max=320) String email,@NotBlank @Size(max=72) String password) {
        @Override public String toString() { return "Login[redacted]"; }
    }
    public record Me(UUID id,String email,String displayName,String role,boolean emailVerified) {
        static Me from(Account account) { return new Me(account.id(),account.email(),account.displayName(),account.role(),account.emailVerifiedAt()!=null); }
    }
    @GetMapping("/csrf") public Map<String,String> csrf(CsrfToken csrf,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        return Map.of("headerName",csrf.getHeaderName(),"token",csrf.getToken());
    }
    @PostMapping("/login") public Me login(@Valid @RequestBody Login input,HttpServletRequest request,HttpServletResponse response) {
        String email=input.email().trim().toLowerCase(Locale.ROOT);
        if(!accounts.allowAttempt(hash("login-ip:"+request.getRemoteAddr()),30,Duration.ofMinutes(5))
                || !accounts.allowAttempt(hash("login-email:"+email),10,Duration.ofMinutes(5))) throw new AuthFailure(429,"RATE_LIMITED");
        var found=accounts.byEmail(email);
        String encoded=found.map(Account::passwordHash).orElse(dummyHash);
        boolean valid=input.password().getBytes(StandardCharsets.UTF_8).length<=72 && passwords.matches(input.password(),encoded);
        if(found.isEmpty() || !valid || !found.get().active()) {
            audits.record(com.example.epub.application.identity.AuditEvents.Action.LOGIN_FAILED,null,null,
                    (String)request.getAttribute(com.example.epub.api.RequestIdFilter.ATTRIBUTE));
            throw new AuthFailure(401,"INVALID_CREDENTIALS");
        }
        audits.record(com.example.epub.application.identity.AuditEvents.Action.LOGIN_SUCCEEDED,found.get().id(),found.get().id(),
                (String)request.getAttribute(com.example.epub.api.RequestIdFilter.ATTRIBUTE));
        sessions.establish(found.get(),request,response);return Me.from(found.get());
    }
    @GetMapping("/me") public Me me(Authentication authentication,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        if(authentication==null || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) throw new AuthFailure(401,"AUTHENTICATION_REQUIRED");
        var account=accounts.byId(principal.userId()).filter(Account::active).orElseThrow(() -> new AuthFailure(401,"AUTHENTICATION_REQUIRED"));
        return Me.from(account);
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
