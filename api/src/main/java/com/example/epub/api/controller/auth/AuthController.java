package com.example.epub.api.controller.auth;

import com.example.epub.api.dto.auth.CurrentUserResponse;
import com.example.epub.api.dto.auth.LoginRequest;
import com.example.epub.api.security.PasswordLogin;
import com.example.epub.api.security.SessionLogin;
import com.example.epub.api.security.SessionPrincipal;
import com.example.epub.core.service.identity.AccountService;
import com.example.epub.core.model.identity.Account;
import com.example.epub.api.security.*;
import com.example.epub.api.exception.AuthFailure;
import com.example.epub.api.dto.auth.*;
import com.example.epub.api.filter.RequestIdFilter;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class AuthController {
    private final AccountService accounts;
    private final PasswordLogin passwords;
    private final SessionLogin sessions;
    public AuthController(AccountService accounts,PasswordLogin passwords,SessionLogin sessions) { this.accounts=accounts;this.passwords=passwords;this.sessions=sessions; }
    @GetMapping("/csrf") public Map<String,String> csrf(CsrfToken csrf,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");return Map.of("headerName",csrf.getHeaderName(),"token",csrf.getToken());
    }
    @PostMapping("/login") public CurrentUserResponse login(@Valid @RequestBody LoginRequest input,HttpServletRequest request,HttpServletResponse response) {
        var account=passwords.authenticate(input.email(),input.password(),request.getRemoteAddr(),(String)request.getAttribute(RequestIdFilter.ATTRIBUTE));
        sessions.establish(account,request,response);return CurrentUserResponse.from(account);
    }
    @GetMapping("/me") public CurrentUserResponse me(Authentication authentication,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        if(authentication==null || !(authentication.getPrincipal() instanceof SessionPrincipal principal))throw new AuthFailure(401,"AUTHENTICATION_REQUIRED");
        var account=accounts.byId(principal.userId()).filter(Account::active).orElseThrow(() -> new AuthFailure(401,"AUTHENTICATION_REQUIRED"));
        return CurrentUserResponse.from(account);
    }
}
