package com.example.epub.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Order(-10) @RestControllerAdvice
public class AuthAdvice {
    private final AuthProblems problems;
    public AuthAdvice(AuthProblems problems) { this.problems=problems; }
    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<?> failure(AuthFailure error,HttpServletRequest request) {
        var response=ResponseEntity.status(error.status()).header("Content-Type","application/problem+json").header("Cache-Control","no-store");
        if(error.status()==429)response.header("Retry-After","300");
        return response.body(problems.body(request,error.status(),error.code(),error.fields()));
    }
}
