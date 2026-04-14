package com.example.epub.core.service.identity;

import com.example.epub.core.repository.identity.JpaUserRepository;

import java.util.Optional;
import com.example.epub.core.service.identity.UserAccounts;
import com.example.epub.core.model.identity.UserCredentials;

public class UserAccountService implements UserAccounts {
    private final JpaUserRepository repository;
    public UserAccountService(JpaUserRepository repository) { this.repository = repository; }
    @Override public Optional<UserCredentials> findByNormalizedEmail(String email) {
        return repository.findByEmail(email).map(user -> new UserCredentials(user.id(), user.email(), user.passwordHash(), user.role(), user.status()));
    }
}
