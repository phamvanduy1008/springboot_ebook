package com.example.epub.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import static org.junit.jupiter.api.Assertions.*;

class MethodPolicyTest {
    static class GuardedUseCase {
        @PreAuthorize("hasRole('ADMIN')") public String execute() {return "ok";}
    }
    @Configuration @EnableMethodSecurity
    static class Config { @Bean GuardedUseCase guardedUseCase() {return new GuardedUseCase();} }
    @Test void methodSecurityActuallyInterceptsTheUseCase() {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            var guarded=context.getBean(GuardedUseCase.class);
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("user",null,AuthorityUtils.createAuthorityList("ROLE_USER")));
            assertThrows(AccessDeniedException.class,guarded::execute);
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("admin",null,AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
            assertEquals("ok",guarded.execute());
        } finally {SecurityContextHolder.clearContext();}
    }
}
