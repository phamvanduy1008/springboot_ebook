package com.example.epub.api.controller.auth;

import com.example.epub.api.dto.auth.AccountResponse;
import com.example.epub.api.dto.auth.ChangePasswordRequest;
import com.example.epub.api.dto.auth.ReauthenticationRequest;
import com.example.epub.api.dto.auth.SessionResponse;
import com.example.epub.api.exception.AuthFailure;
import com.example.epub.api.security.AuthRequestChecks;
import com.example.epub.api.security.GoogleLinkIntent;
import com.example.epub.api.security.PasswordLogin;
import com.example.epub.api.security.SessionLogin;
import com.example.epub.api.security.SessionPrincipal;

import com.example.epub.core.service.identity.*;
import com.example.epub.core.model.identity.Account;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
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
    private final PasswordLogin authentication;
    private final PasswordEncoder passwords;
    private final SessionLogin login;
    private final boolean googleEnabled;
    public AccountController(AccountSecurity security,PasswordLogin authentication,PasswordEncoder passwords,SessionLogin login,Environment env) {
        this.security=security;this.authentication=authentication;this.passwords=passwords;this.login=login;
        googleEnabled=env.getProperty("app.google.enabled",Boolean.class,false);
    }

    private SessionPrincipal principal() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null || !(authentication.getPrincipal() instanceof SessionPrincipal principal))throw new AuthFailure(401,"AUTHENTICATION_REQUIRED");
        return principal;
    }
    private AccountResponse view(Account account) {return new AccountResponse(account.id(),account.email(),account.displayName(),account.bio(),account.role(),account.emailVerifiedAt()!=null,account.passwordHash()!=null,security.providers(account.id()));}
    private Account current(SessionPrincipal principal) {return security.profile(principal.userId(),principal.credentialEpoch());}
    @GetMapping public AccountResponse profile(HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");return view(current(principal()));
    }
    @PatchMapping public AccountResponse update(@RequestBody Map<String,Object> input,HttpServletResponse response) {
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
    @PostMapping("/change-password") public AccountResponse changePassword(@Valid @RequestBody ChangePasswordRequest input,HttpServletRequest request,HttpServletResponse response) {
        var principal=principal();var before=current(principal);authentication.reauthenticate(before,input.currentPassword(),request.getRemoteAddr());
        AuthRequestChecks.validatePassword(input.newPassword());
        var after=security.changePassword(before.id(),principal.credentialEpoch(),before.passwordHash(),passwords.encode(input.newPassword()),
                (String)request.getAttribute(com.example.epub.api.filter.RequestIdFilter.ATTRIBUTE));
        if(request.getSession(false)!=null)request.getSession(false).invalidate();
        login.establish(after,request,response);return view(after);
    }
    @GetMapping("/sessions") public List<SessionResponse> sessions(HttpServletRequest request,HttpServletResponse response) {
        var principal=principal();response.setHeader("Cache-Control","no-store");
        return security.sessions(principal.userId(),principal.credentialEpoch(),request.getSession().getId()).stream().map(session -> new SessionResponse(session.id(),session.createdAt(),session.lastAccessAt(),session.current())).toList();
    }
    @DeleteMapping("/sessions/{id}") public ResponseEntity<?> revoke(@PathVariable UUID id,HttpServletRequest request) {
        var principal=principal();
        boolean current=security.revoke(principal.userId(),principal.credentialEpoch(),id,request.getSession().getId());
        if(current) {request.getSession().invalidate();SecurityContextHolder.clearContext();}
        return ResponseEntity.noContent().header("Cache-Control","no-store").build();
    }
    @PostMapping("/google/link") public Map<String,String> link(@Valid @RequestBody ReauthenticationRequest input,HttpServletRequest request,HttpServletResponse response) {
        if(!googleEnabled)throw new AuthFailure(409,"GOOGLE_LOGIN_DISABLED");
        var principal=principal();authentication.reauthenticate(current(principal),input.currentPassword(),request.getRemoteAddr());
        request.getSession().setAttribute(GoogleLinkIntent.PENDING,new GoogleLinkIntent(principal.userId(),principal.credentialEpoch(),Instant.now()));
        response.setHeader("Cache-Control","no-store");
        return Map.of("authorizationUrl","/api/v1/oauth2/authorization/google?intent=link&returnTo=/account");
    }
}
