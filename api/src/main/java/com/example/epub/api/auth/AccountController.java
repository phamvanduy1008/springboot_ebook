package com.example.epub.api.auth;

import com.example.epub.application.identity.*;
import com.example.epub.domain.identity.Account;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/account")
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class AccountController {
    private final AccountSecurity security;
    private final AccountStore accounts;
    private final PasswordEncoder passwords;
    private final SessionLogin login;
    private final boolean googleEnabled;
    public AccountController(AccountSecurity security,AccountStore accounts,PasswordEncoder passwords,SessionLogin login,Environment env) {
        this.security=security;this.accounts=accounts;this.passwords=passwords;this.login=login;
        googleEnabled=env.getProperty("app.google.enabled",Boolean.class,false);
    }
    public record Profile(UUID id,String email,String displayName,String bio,String role,boolean emailVerified,boolean hasPassword,List<String> providers) { }
    public record ChangePassword(@NotBlank @Size(max=72) String currentPassword,@NotBlank @Size(min=12,max=72) String newPassword) {
        @Override public String toString() {return "ChangePassword[redacted]";}
    }
    public record Reauthenticate(@NotBlank @Size(max=72) String currentPassword) {
        @Override public String toString() {return "Reauthenticate[redacted]";}
    }
    private SessionPrincipal principal() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null || !(authentication.getPrincipal() instanceof SessionPrincipal principal))throw new AuthFailure(401,"AUTHENTICATION_REQUIRED");
        return principal;
    }
    private Profile view(Account account) {return new Profile(account.id(),account.email(),account.displayName(),account.bio(),account.role(),account.emailVerifiedAt()!=null,account.passwordHash()!=null,security.providers(account.id()));}
    private Account current(SessionPrincipal principal) {return security.profile(principal.userId(),principal.credentialEpoch());}
    private void checkPassword(Account account,String supplied,HttpServletRequest request) {
        if(!accounts.allowAttempt(AuthController.hash("reauth-user:"+account.id()),5,Duration.ofMinutes(5))
                || !accounts.allowAttempt(AuthController.hash("reauth-ip:"+request.getRemoteAddr()),20,Duration.ofMinutes(5)))throw new AuthFailure(429,"RATE_LIMITED");
        if(account.passwordHash()==null)throw new AuthFailure(409,"PASSWORD_NOT_AVAILABLE");
        if(supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72 || !passwords.matches(supplied,account.passwordHash()))
            throw new AuthFailure(400,"INVALID_CURRENT_PASSWORD",Map.of("currentPassword","The current password is incorrect."));
    }
    @GetMapping public Profile profile(HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");return view(current(principal()));
    }
    @PatchMapping public Profile update(@RequestBody Map<String,Object> input,HttpServletResponse response) {
        if(input.isEmpty() || !Set.of("displayName","bio").containsAll(input.keySet()))throw new AuthFailure(400,"INVALID_REQUEST",Map.of("body","Only displayName and bio can be changed."));
        String name=null,bio=null;
        if(input.containsKey("displayName")) {
            if(!(input.get("displayName") instanceof String value) || value.strip().isBlank() || value.codePointCount(0,value.length())>60)
                throw new AuthFailure(400,"INVALID_REQUEST",Map.of("displayName","Use 1 to 60 characters."));
            name=((String)input.get("displayName")).strip();
        }
        if(input.containsKey("bio")) {
            if(!(input.get("bio") instanceof String value) || value.codePointCount(0,value.length())>240)
                throw new AuthFailure(400,"INVALID_REQUEST",Map.of("bio","Use at most 240 characters."));
            bio=(String)input.get("bio");
        }
        var principal=principal();response.setHeader("Cache-Control","no-store");
        return view(security.updateProfile(principal.userId(),principal.credentialEpoch(),name,bio));
    }
    @PostMapping("/change-password") public Profile changePassword(@Valid @RequestBody ChangePassword input,HttpServletRequest request,HttpServletResponse response) {
        var principal=principal();var before=current(principal);checkPassword(before,input.currentPassword(),request);
        RegistrationController.validatePassword(input.newPassword());
        var after=security.changePassword(before.id(),principal.credentialEpoch(),before.passwordHash(),passwords.encode(input.newPassword()),
                (String)request.getAttribute(com.example.epub.api.RequestIdFilter.ATTRIBUTE));
        if(request.getSession(false)!=null)request.getSession(false).invalidate();
        login.establish(after,request,response);return view(after);
    }
    @GetMapping("/sessions") public List<AccountSecurity.SessionInfo> sessions(HttpServletRequest request,HttpServletResponse response) {
        var principal=principal();response.setHeader("Cache-Control","no-store");
        return security.sessions(principal.userId(),principal.credentialEpoch(),request.getSession().getId());
    }
    @DeleteMapping("/sessions/{id}") public ResponseEntity<?> revoke(@PathVariable UUID id,HttpServletRequest request) {
        var principal=principal();
        boolean current=security.revoke(principal.userId(),principal.credentialEpoch(),id,request.getSession().getId());
        if(current) {request.getSession().invalidate();SecurityContextHolder.clearContext();}
        return ResponseEntity.noContent().header("Cache-Control","no-store").build();
    }
    @PostMapping("/google/link") public Map<String,String> link(@Valid @RequestBody Reauthenticate input,HttpServletRequest request,HttpServletResponse response) {
        if(!googleEnabled)throw new AuthFailure(409,"GOOGLE_LOGIN_DISABLED");
        var principal=principal();checkPassword(current(principal),input.currentPassword(),request);
        request.getSession().setAttribute(GoogleLinkIntent.PENDING,new GoogleLinkIntent(principal.userId(),principal.credentialEpoch(),Instant.now()));
        response.setHeader("Cache-Control","no-store");
        return Map.of("authorizationUrl","/api/v1/oauth2/authorization/google?intent=link&returnTo=/account");
    }
}
