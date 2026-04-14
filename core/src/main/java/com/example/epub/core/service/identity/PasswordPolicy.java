package com.example.epub.core.service.identity;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {
    private PasswordPolicy() { }
    public static boolean valid(String value) {
        return value!=null && !value.isBlank() && value.length()>=12 && value.getBytes(StandardCharsets.UTF_8).length<=72;
    }
}
