package com.example.epub.application.identity;

import com.example.epub.domain.identity.Account;
import java.time.Instant;
import java.util.*;

public interface AccountSecurity {
    record SessionInfo(UUID id,Instant createdAt,Instant lastAccessAt,boolean current) { }
    Account profile(UUID user,Instant epoch);
    Account updateProfile(UUID user,Instant epoch,String displayName,String bio);
    Account changePassword(UUID user,Instant epoch,String expectedHash,String newHash,String requestId);
    List<String> providers(UUID user);
    List<SessionInfo> sessions(UUID user,Instant epoch,String currentSessionId);
    boolean revoke(UUID user,Instant epoch,UUID sessionId,String currentSessionId);
}
