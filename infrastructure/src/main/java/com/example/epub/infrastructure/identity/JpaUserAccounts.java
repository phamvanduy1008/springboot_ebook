package com.example.epub.infrastructure.identity;

import java.util.Optional;
import com.example.epub.application.identity.UserAccounts;
import com.example.epub.domain.identity.UserCredentials;

public class JpaUserAccounts implements UserAccounts {
    private final JpaUserRepository repository;
    public JpaUserAccounts(JpaUserRepository repository) { this.repository = repository; }
    @Override public Optional<UserCredentials> findByNormalizedEmail(String email) {
        return repository.findByEmail(email).map(user -> new UserCredentials(user.id(), user.email(), user.passwordHash(), user.role(), user.status()));
    }
}
