package com.example.epub.core.service.identity;

public interface Registration {
    void register(String normalizedEmail,String displayName,String passwordHash);
    void resend(String normalizedEmail);
    void verify(String token);
    class InvalidToken extends RuntimeException {public InvalidToken() {super("Invalid or expired token");}}
    class MailUnavailable extends RuntimeException {public MailUnavailable() {super("Mail is unavailable");}}
}
