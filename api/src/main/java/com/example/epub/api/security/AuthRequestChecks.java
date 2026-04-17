package com.example.epub.api.security;
import com.example.epub.core.service.identity.AccountService;
import com.example.epub.core.service.identity.PasswordPolicy;
import com.example.epub.api.exception.AuthFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Component @ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type=org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class AuthRequestChecks {
    private final AccountService accounts;
    public AuthRequestChecks(AccountService accounts) { this.accounts=accounts; }
    public static String normalize(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    public void throttle(String ip,String email,String operation) {
        if(!accounts.allowAttempt(hash(operation+"-ip:"+ip),20,Duration.ofMinutes(5))
                || !accounts.allowAttempt(hash(operation+"-email:"+email),5,Duration.ofMinutes(5)))throw new AuthFailure(429,"RATE_LIMITED");
    }
    public void limitIp(String ip,String operation) {
        if(!accounts.allowAttempt(hash(operation+"-ip:"+ip),30,Duration.ofMinutes(5)))throw new AuthFailure(429,"RATE_LIMITED");
    }
    public static void validatePassword(String value) {
        if(!PasswordPolicy.valid(value))throw new AuthFailure(400,"INVALID_REQUEST",Map.of("password","Use at least 12 characters and at most 72 UTF-8 bytes."));
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
