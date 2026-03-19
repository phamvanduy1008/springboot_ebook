package com.example.epub.api.auth;

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
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean UserDetailsService noGeneratedUser() { return name -> {throw new UsernameNotFoundException("Not available");}; }
    @Bean SecurityFilterChain security(HttpSecurity http,AuthProblems problems,Environment environment) throws Exception {
        http.formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .securityContext(context -> context.securityContextRepository(new HttpSessionSecurityContextRepository()))
                .exceptionHandling(errors -> errors
                    .authenticationEntryPoint((request,response,error) -> problems.write(request,response,401,"AUTHENTICATION_REQUIRED"))
                    .accessDeniedHandler((request,response,error) -> problems.write(request,response,403,"ACCESS_DENIED")))
                .logout(logout -> logout.logoutUrl("/api/v1/auth/logout").invalidateHttpSession(true).clearAuthentication(true)
                    .deleteCookies("SESSION").logoutSuccessHandler((request,response,authentication) -> {
                        response.setHeader("Cache-Control","no-store");response.setStatus(204);
                    }));
        if(environment.acceptsProfiles(Profiles.of("test"))) {
            // Service-free contract/smoke profile. Never combined with prod (runtime validation rejects it).
            http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        } else {
            http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**", "/api/v1/health",
                    "/api/v1/auth/csrf", "/api/v1/auth/login").permitAll()
                .anyRequest().authenticated());
        }
        return http.build();
    }
}
