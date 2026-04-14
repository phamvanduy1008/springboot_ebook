package com.example.epub.api.auth;

import com.example.epub.core.service.identity.AccountService;

import com.example.epub.core.service.identity.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.user.*;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component @ConditionalOnProperty(name="app.google.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class GoogleLogin {
    private final ClientRegistrationRepository clients;
    private final GoogleAccounts accounts;
    private final SessionLogin sessions;
    private final AuditEvents audits;
    private final String frontend;
    private final AccountService store;
    private final AuthProblems problems;
    private final GoogleAuthorizationRequests requests=new GoogleAuthorizationRequests();
    public GoogleLogin(ClientRegistrationRepository clients,GoogleAccounts accounts,SessionLogin sessions,AuditEvents audits,Environment env,AccountService store,AuthProblems problems) {
        this.clients=clients;this.accounts=accounts;this.sessions=sessions;this.audits=audits;
        frontend=env.getRequiredProperty("app.public-origin")+(env.getProperty("app.frontend.hash-routing",Boolean.class,true)?"/#":"");
        this.store=store;this.problems=problems;
    }
    public void configure(HttpSecurity http) throws Exception {
        http.addFilterBefore(new org.springframework.web.filter.OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,jakarta.servlet.FilterChain chain)
                    throws jakarta.servlet.ServletException,IOException {
                if("GET".equals(request.getMethod()) && "/api/v1/oauth2/authorization/google".equals(request.getServletPath())
                        && !store.allowAttempt(AuthController.hash("google-ip:"+request.getRemoteAddr()),30,Duration.ofMinutes(5))) {
                    problems.write(request,response,429,"RATE_LIMITED");return;
                }
                chain.doFilter(request,response);
            }
        },OAuth2AuthorizationRequestRedirectFilter.class);
        var resolver=new DefaultOAuth2AuthorizationRequestResolver(clients,"/api/v1/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        var tokenClient=new RestClientAuthorizationCodeTokenResponseClient();
        tokenClient.setRestClient(RestClient.builder().requestFactory(factory).messageConverters(converters -> {
            converters.clear();converters.add(new FormHttpMessageConverter());converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
        }).defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build());
        http.oauth2Login(oauth -> oauth.clientRegistrationRepository(clients)
            .authorizationEndpoint(endpoint -> endpoint.baseUri("/api/v1/oauth2/authorization")
                .authorizationRequestResolver(resolver).authorizationRequestRepository(requests))
            .redirectionEndpoint(endpoint -> endpoint.baseUri("/api/v1/login/oauth2/code/*"))
            .tokenEndpoint(endpoint -> endpoint.accessTokenResponseClient(tokenClient))
            .userInfoEndpoint(endpoint -> endpoint.oidcUserService(userRequest -> new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("OIDC_USER")),userRequest.getIdToken())))
            .authorizedClientRepository(new NoStoredTokens())
            .securityContextRepository(new NullSecurityContextRepository())
            .successHandler(this::success)
            .failureHandler((request,response,error) -> fail(request,response)));
    }
    private void success(HttpServletRequest request,HttpServletResponse response,Authentication authentication) throws IOException {
        try {
            var oidc=(OidcUser)authentication.getPrincipal();String email=oidc.getEmail();String domain=oidc.getClaimAsString("hd");
            boolean authoritative=Boolean.TRUE.equals(oidc.getEmailVerified()) && email!=null
                    && (email.toLowerCase(java.util.Locale.ROOT).endsWith("@gmail.com") || (domain!=null && !domain.isBlank()));
            com.example.epub.core.model.identity.Account account;
            boolean linking=request.getAttribute(GoogleLinkIntent.ATTRIBUTE) instanceof GoogleLinkIntent;
            if(linking) {
                var intent=(GoogleLinkIntent)request.getAttribute(GoogleLinkIntent.ATTRIBUTE);
                Object stored=request.getAttribute(CurrentAccountFilter.PRINCIPAL_ATTRIBUTE);
                if(!intent.valid() || !Boolean.TRUE.equals(oidc.getEmailVerified()) || !(stored instanceof SessionPrincipal principal)
                        || !principal.userId().equals(intent.userId()) || !principal.credentialEpoch().equals(intent.credentialEpoch()))throw new GoogleAccounts.Rejected();
                account=accounts.link(intent.userId(),intent.credentialEpoch(),oidc.getIssuer().toString(),oidc.getSubject());
            } else {
                account=accounts.login(oidc.getIssuer().toString(),oidc.getSubject(),email,
                        Boolean.TRUE.equals(oidc.getEmailVerified()),authoritative,oidc.getFullName());
            }
            sessions.establish(account,request,response);
            audits.record(linking?AuditEvents.Action.GOOGLE_LINKED:AuditEvents.Action.GOOGLE_LOGIN,account.id(),account.id(),(String)request.getAttribute(com.example.epub.api.RequestIdFilter.ATTRIBUTE));
            response.sendRedirect(frontend+GoogleAuthorizationRequests.safeReturnTo((String)request.getAttribute(GoogleAuthorizationRequests.RETURN_TO)));
        } catch(GoogleAccounts.Rejected | AccessPolicy.Denied invalid) {fail(request,response);}
    }
    private void fail(HttpServletRequest request,HttpServletResponse response) throws IOException {
        requests.clear(request,response);SecurityContextHolder.clearContext();
        Object prior=request.getAttribute(CurrentAccountFilter.PRINCIPAL_ATTRIBUTE);
        var existing=prior instanceof SessionPrincipal principal?store.byId(principal.userId())
                .filter(account -> account.active() && account.passwordChangedAt().equals(principal.credentialEpoch())):java.util.Optional.<com.example.epub.core.model.identity.Account>empty();
        if(existing.isPresent() && request.getSession(false)!=null) {
            sessions.restore(existing.get(),request,response);
        } else {
            new org.springframework.security.web.context.HttpSessionSecurityContextRepository()
                    .saveContext(SecurityContextHolder.createEmptyContext(),request,response);
        }
        response.setHeader("Cache-Control","no-store");response.sendRedirect(frontend+"/login?error=GOOGLE_LOGIN_FAILED");
    }
    static class NoStoredTokens implements OAuth2AuthorizedClientRepository {
        @Override public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String registrationId,Authentication principal,HttpServletRequest request) {return null;}
        @Override public void saveAuthorizedClient(OAuth2AuthorizedClient client,Authentication principal,HttpServletRequest request,HttpServletResponse response) { }
        @Override public void removeAuthorizedClient(String registrationId,Authentication principal,HttpServletRequest request,HttpServletResponse response) { }
    }
}
