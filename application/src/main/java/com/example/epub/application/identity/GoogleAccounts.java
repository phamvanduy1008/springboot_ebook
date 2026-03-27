package com.example.epub.application.identity;

import com.example.epub.domain.identity.Account;

public interface GoogleAccounts {
    /** Claims must come from an ID token already verified by the OIDC implementation. */
    Account login(String issuer,String subject,String email,boolean verified,boolean authoritative,String displayName);
    class Rejected extends RuntimeException { public Rejected() {super("Google sign-in could not be completed");} }
}
