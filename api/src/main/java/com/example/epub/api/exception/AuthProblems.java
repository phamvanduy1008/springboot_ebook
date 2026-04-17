package com.example.epub.api.exception;

import com.example.epub.api.filter.RequestIdFilter;
import com.example.epub.api.dto.common.ProblemResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AuthProblems {
    public ProblemResponse body(HttpServletRequest request,int status,String code,Map<String,String> fields) {
        Object id=request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return new ProblemResponse("about:blank",HttpStatus.valueOf(status).getReasonPhrase(),status,code,
                switch(status) {case 401 -> "Authentication is required or credentials are invalid.";
                    case 403 -> "Access is denied.";case 429 -> "Too many requests. Try again later.";
                    default -> "The request could not be completed.";},
                id instanceof String value?value:UUID.randomUUID().toString(),fields);
    }
    public void write(HttpServletRequest request,HttpServletResponse response,int status,String code) throws IOException {
        response.setStatus(status);response.setContentType("application/problem+json");
        response.setHeader("Cache-Control","no-store");
        if(status==429) response.setHeader("Retry-After","300");
        response.getWriter().write(JsonMapper.builder().build().writeValueAsString(body(request,status,code,Map.of())));
    }
}
