package com.example.epub.core.repository.identity;

import com.example.epub.core.repository.identity.AccountRepository;

import com.example.epub.core.model.identity.Account;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {
    Optional<Account> byEmail(String normalizedEmail);
    Optional<Account> byId(UUID id);
    /** Atomically count requests in a bounded time window, shared between API instances. */
    boolean allowAttempt(String hashedKey, int limit, Duration window);
}
