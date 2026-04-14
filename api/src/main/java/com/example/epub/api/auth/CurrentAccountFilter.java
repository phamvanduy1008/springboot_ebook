package com.example.epub.api.auth;

import com.example.epub.core.service.identity.AccountService;

import com.example.epub.core.service.identity.AccountService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/** Every authenticated request rechecks the current database role/status and password epoch. */
public class CurrentAccountFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL_ATTRIBUTE="auth.currentApplicationPrincipal";
    private final AccountService accounts;
    public CurrentAccountFilter(AccountService accounts) {this.accounts=accounts;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        var context=SecurityContextHolder.getContext();var auth=context.getAuthentication();
        if(auth!=null && auth.getPrincipal() instanceof SessionPrincipal principal) {
            var found=accounts.byId(principal.userId());
            var session=request.getSession(false);
            if(found.isEmpty() || !found.get().active() || !found.get().passwordChangedAt().equals(principal.credentialEpoch())) {
                if(session!=null)session.invalidate();SecurityContextHolder.clearContext();
            } else {
                request.setAttribute(PRINCIPAL_ATTRIBUTE,principal);
                var fresh=UsernamePasswordAuthenticationToken.authenticated(principal,null,
                        List.of(new SimpleGrantedAuthority("ROLE_"+found.get().role())));
                context.setAuthentication(fresh);
                new HttpSessionSecurityContextRepository().saveContext(context,request,response);
            }
        }
        chain.doFilter(request,response);
    }
}
