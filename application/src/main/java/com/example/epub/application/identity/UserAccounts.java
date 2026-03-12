package com.example.epub.application.identity;

import java.util.Optional;
import com.example.epub.domain.identity.UserCredentials;

public interface UserAccounts {
    Optional<UserCredentials> findByNormalizedEmail(String email);
}
