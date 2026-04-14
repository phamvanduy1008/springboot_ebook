package com.example.epub.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Order(-10) @RestControllerAdvice
public class AuthAdvice {
    private final AuthProblems problems;
    public AuthAdvice(AuthProblems problems) { this.problems=problems; }
    @ExceptionHandler(com.example.epub.core.service.identity.Registration.InvalidToken.class)
    ResponseEntity<?> invalidToken(com.example.epub.core.service.identity.Registration.InvalidToken error,HttpServletRequest request) {
        return failure(new AuthFailure(400,"INVALID_OR_EXPIRED_TOKEN"),request);
    }
    @ExceptionHandler(com.example.epub.core.service.identity.Registration.MailUnavailable.class)
    ResponseEntity<?> mailUnavailable(com.example.epub.core.service.identity.Registration.MailUnavailable error,HttpServletRequest request) {
        return failure(new AuthFailure(503,"MAIL_UNAVAILABLE"),request);
    }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<?> deniedByMethodSecurity(org.springframework.security.access.AccessDeniedException denied,HttpServletRequest request) {
        return failure(new AuthFailure(403,"ACCESS_DENIED"),request);
    }
    @ExceptionHandler(com.example.epub.core.service.identity.AccessPolicy.Denied.class)
    ResponseEntity<?> denied(com.example.epub.core.service.identity.AccessPolicy.Denied denied,HttpServletRequest request) {
        return failure(new AuthFailure(denied.hidden()?404:403,denied.hidden()?"NOT_FOUND":"ACCESS_DENIED"),request);
    }
    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<?> failure(AuthFailure error,HttpServletRequest request) {
        var response=ResponseEntity.status(error.status()).header("Content-Type","application/problem+json").header("Cache-Control","no-store");
        if(error.status()==429)response.header("Retry-After","300");
        return response.body(problems.body(request,error.status(),error.code(),error.fields()));
    }
}
