package com.example.epub.api.exception;
import com.example.epub.core.service.epub.EpubFailure;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.*;
@org.springframework.core.annotation.Order(-20) @RestControllerAdvice
public class EpubAdvice {
    private final AuthProblems problems;
    public EpubAdvice(AuthProblems problems) {this.problems=problems;}
    @ExceptionHandler(EpubFailure.class) public void epub(EpubFailure failure,HttpServletRequest request,HttpServletResponse response) throws java.io.IOException {
        int status=switch(failure.reason()) {case INVALID_INPUT->400;case NOT_FOUND->404;case REVISION_CONFLICT,IDEMPOTENCY_CONFLICT,INVALID_STATE->409;case TOO_LARGE->413;case UNSUPPORTED->422;case QUOTA_EXCEEDED->429;case STORAGE_UNAVAILABLE->503;};
        response.setHeader("Cache-Control","no-store");problems.write(request,response,status,"EPUB_"+failure.reason().name());
    }
}
