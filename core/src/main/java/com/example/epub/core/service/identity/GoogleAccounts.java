package com.example.epub.core.service.identity;

import com.example.epub.core.model.identity.Account;

public interface GoogleAccounts {
    /** Claims must come from an ID token already verified by the OIDC implementation. */
    Account login(String issuer,String subject,String email,boolean verified,boolean authoritative,String displayName);
    Account link(java.util.UUID user,java.time.Instant credentialEpoch,String issuer,String subject);
    class Rejected extends RuntimeException { public Rejected() {super("Google sign-in could not be completed");} }
}
