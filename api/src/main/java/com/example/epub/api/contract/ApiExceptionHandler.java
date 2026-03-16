package com.example.epub.api.contract;

import com.example.epub.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @Override protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String,String> fields=new LinkedHashMap<>();
        if (exception instanceof BindException binding) {
            binding.getBindingResult().getFieldErrors().forEach(error -> fields.putIfAbsent(error.getField(),"Invalid value"));
        }
        return response(status.value(),headers,((ServletWebRequest)request).getRequest(),fields);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> invalidArgument(IllegalArgumentException exception,HttpServletRequest request) {
        return response(400,new HttpHeaders(),request,Map.of());
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception exception,HttpServletRequest request) {
        // Do not copy exception messages (SQL/body/credentials) to responses or logs.
        logger.error("Unhandled request failure; exception type="+exception.getClass().getSimpleName());
        return response(500,new HttpHeaders(),request,Map.of());
    }
    private ResponseEntity<Object> response(int status,HttpHeaders source,HttpServletRequest request,Map<String,String> fields) {
        String code=switch(status) {
            case 400 -> "INVALID_REQUEST";case 401 -> "AUTHENTICATION_REQUIRED";case 403 -> "ACCESS_DENIED";
            case 404 -> "NOT_FOUND";case 405 -> "METHOD_NOT_ALLOWED";case 409 -> "CONFLICT";
            case 413 -> "PAYLOAD_TOO_LARGE";case 415 -> "UNSUPPORTED_MEDIA_TYPE";case 422 -> "VALIDATION_FAILED";
            case 429 -> "RATE_LIMITED";case 503 -> "SERVICE_UNAVAILABLE";default -> status>=500 ? "INTERNAL_ERROR" : "REQUEST_FAILED";
        };
        String detail=switch(status) {case 400 -> "The request is invalid.";case 401 -> "Authentication is required.";
            case 403 -> "Access is denied.";case 404 -> "The resource was not found.";
            default -> status>=500 ? "The server could not complete the request." : "The request could not be completed.";};
        Object id=request.getAttribute(RequestIdFilter.ATTRIBUTE);
        String requestId=id instanceof String value ? value : UUID.randomUUID().toString();
        HttpHeaders headers=new HttpHeaders();headers.addAll(source);headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        headers.set("X-Request-ID",requestId);
        var body=new ProblemResponse("about:blank",HttpStatus.valueOf(status).getReasonPhrase(),status,code,detail,requestId,fields);
        return new ResponseEntity<>(body,headers,HttpStatusCode.valueOf(status));
    }
}
