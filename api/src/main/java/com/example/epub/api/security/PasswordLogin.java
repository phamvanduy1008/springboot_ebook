package com.example.epub.api.security;
import com.example.epub.core.service.identity.*;
import com.example.epub.core.model.identity.Account;
import com.example.epub.api.exception.AuthFailure;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
/** Password authentication/re-authentication; servlet session creation stays in SessionLogin. */
@Component @ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type=org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class PasswordLogin {
    private final AccountService accounts;
    private final PasswordEncoder passwords;
    private final AuditEvents audits;
    private final String dummyHash;
    public PasswordLogin(AccountService accounts,PasswordEncoder passwords,AuditEvents audits) {
        this.accounts=accounts;this.passwords=passwords;this.audits=audits;dummyHash=passwords.encode(UUID.randomUUID().toString());
    }
    public Account authenticate(String email,String password,String ip,String requestId) {
        email=AuthRequestChecks.normalize(email);
        if(!accounts.allowAttempt(AuthRequestChecks.hash("login-ip:"+ip),30,Duration.ofMinutes(5))
                || !accounts.allowAttempt(AuthRequestChecks.hash("login-email:"+email),10,Duration.ofMinutes(5)))throw new AuthFailure(429,"RATE_LIMITED");
        var found=accounts.byEmail(email);
        String encoded=found.map(Account::passwordHash).orElse(dummyHash);
        boolean valid=password.getBytes(StandardCharsets.UTF_8).length<=72 && passwords.matches(password,encoded);
        if(found.isEmpty() || !valid || !found.get().active()) {
            audits.record(AuditEvents.Action.LOGIN_FAILED,null,null,requestId);throw new AuthFailure(401,"INVALID_CREDENTIALS");
        }
        audits.record(AuditEvents.Action.LOGIN_SUCCEEDED,found.get().id(),found.get().id(),requestId);return found.get();
    }
    public void reauthenticate(Account account,String supplied,String ip) {
        if(!accounts.allowAttempt(AuthRequestChecks.hash("reauth-user:"+account.id()),5,Duration.ofMinutes(5))
                || !accounts.allowAttempt(AuthRequestChecks.hash("reauth-ip:"+ip),20,Duration.ofMinutes(5)))throw new AuthFailure(429,"RATE_LIMITED");
        if(account.passwordHash()==null)throw new AuthFailure(409,"PASSWORD_NOT_AVAILABLE");
        if(supplied.getBytes(StandardCharsets.UTF_8).length>72 || !passwords.matches(supplied,account.passwordHash()))
            throw new AuthFailure(400,"INVALID_CURRENT_PASSWORD",Map.of("currentPassword","The current password is incorrect."));
    }
}
