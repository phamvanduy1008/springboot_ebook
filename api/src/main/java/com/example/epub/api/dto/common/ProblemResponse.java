package com.example.epub.api.dto.common;

import java.util.Map;

public record ProblemResponse(String type, String title, int status, String code, String detail,
        String requestId, Map<String,String> fieldErrors) {
    public ProblemResponse { fieldErrors=Map.copyOf(fieldErrors); }
}
