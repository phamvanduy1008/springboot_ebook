package com.example.epub.api.auth;

import com.example.epub.core.service.identity.AccountService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration(proxyBeanMethods=false)
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean UserDetailsService noGeneratedUser() { return name -> {throw new UsernameNotFoundException("Not available");}; }
    @Bean SecurityFilterChain security(HttpSecurity http,AuthProblems problems,Environment environment,
            org.springframework.beans.factory.ObjectProvider<com.example.epub.core.service.identity.AccountService> accounts,
            org.springframework.beans.factory.ObjectProvider<com.example.epub.core.service.identity.AuditEvents> audits,
            org.springframework.beans.factory.ObjectProvider<GoogleLogin> google) throws Exception {
        http.formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .securityContext(context -> context.securityContextRepository(new HttpSessionSecurityContextRepository()))
                .exceptionHandling(errors -> errors
                    .authenticationEntryPoint((request,response,error) -> problems.write(request,response,401,"AUTHENTICATION_REQUIRED"))
                    .accessDeniedHandler((request,response,error) -> problems.write(request,response,403,"ACCESS_DENIED")))
                .logout(logout -> logout.logoutUrl("/api/v1/auth/logout").invalidateHttpSession(true).clearAuthentication(true)
                    .deleteCookies("SESSION").logoutSuccessHandler((request,response,authentication) -> {
                        if(authentication!=null && authentication.getPrincipal() instanceof SessionPrincipal principal && audits.getIfAvailable()!=null)
                            audits.getObject().record(com.example.epub.core.service.identity.AuditEvents.Action.LOGOUT,principal.userId(),principal.userId(),
                                (String)request.getAttribute(com.example.epub.api.RequestIdFilter.ATTRIBUTE));
                        response.setHeader("Cache-Control","no-store");response.setStatus(204);
                    }));
        if(environment.acceptsProfiles(Profiles.of("test"))) {
            // Service-free contract/smoke profile. Never combined with prod (runtime validation rejects it).
            http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        } else {
            if(google.getIfAvailable()!=null)google.getObject().configure(http);
            http.addFilterAfter(new CurrentAccountFilter(accounts.getObject()),org.springframework.security.web.context.SecurityContextHolderFilter.class);
            http.authorizeHttpRequests(auth -> auth
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/v1/books/**","/api/v1/categories/**","/api/v1/home","/api/v1/rankings/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**", "/api/v1/health",
                    "/api/v1/auth/csrf", "/api/v1/auth/login", "/api/v1/auth/capabilities",
                    "/api/v1/auth/register", "/api/v1/auth/verify-email", "/api/v1/auth/resend-verification",
                    "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password",
                    "/api/v1/oauth2/authorization/**", "/api/v1/login/oauth2/code/**").permitAll()
                .anyRequest().authenticated());
        }
        return http.build();
    }
}
