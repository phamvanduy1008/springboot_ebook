package com.example.epub.core.service.identity;

public interface PasswordRecovery {
    void forgot(String normalizedEmail);
    void reset(String token,String newPasswordHash,String requestId);
}
