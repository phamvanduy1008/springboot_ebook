package com.example.epub.api.auth;

import com.example.epub.domain.identity.Account;
import jakarta.servlet.http.*;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Component;

@Component
public class SessionLogin {
    private final HttpSessionSecurityContextRepository contexts=new HttpSessionSecurityContextRepository();
    public void establish(Account account,HttpServletRequest request,HttpServletResponse response) {
        request.getSession();request.changeSessionId();
        new HttpSessionCsrfTokenRepository().saveToken(null,request,response);
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new SessionPrincipal(account.id()),null,
                List.of(new SimpleGrantedAuthority("ROLE_"+account.role()))));
        SecurityContextHolder.setContext(context);contexts.saveContext(context,request,response);
        response.setHeader("Cache-Control","no-store");
    }
}
