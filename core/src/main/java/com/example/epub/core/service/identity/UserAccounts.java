package com.example.epub.core.service.identity;

import java.util.Optional;
import com.example.epub.core.model.identity.UserCredentials;

public interface UserAccounts {
    Optional<UserCredentials> findByNormalizedEmail(String email);
}
