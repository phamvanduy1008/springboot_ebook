package com.example.epub.api.exception;
import com.example.epub.core.service.catalog.CatalogFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@Order(-20) @RestControllerAdvice
public class CatalogAdvice {
    private final AuthProblems problems;
    public CatalogAdvice(AuthProblems problems) {this.problems=problems;}
    @ExceptionHandler(CatalogFailure.class) public ResponseEntity<?> failure(CatalogFailure error,HttpServletRequest request) {
        int status=switch(error.reason()) {case RATE_LIMITED -> 429;case INVALID_REQUEST -> 400;case NOT_FOUND -> 404;default -> 409;};
        return ResponseEntity.status(status).header("Content-Type","application/problem+json").header("Cache-Control","no-store").body(problems.body(request,status,error.reason().name(),error.fields()));
    }
}
