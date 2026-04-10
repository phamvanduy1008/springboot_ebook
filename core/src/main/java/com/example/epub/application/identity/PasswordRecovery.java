package com.example.epub.application.identity;

public interface PasswordRecovery {
    void forgot(String normalizedEmail);
    void reset(String token,String newPasswordHash,String requestId);
}
