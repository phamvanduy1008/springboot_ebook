package com.example.epub.api.dto.auth;

import java.util.*;
import com.example.epub.core.model.identity.Account;

public record CurrentUserResponse(UUID id,String email,String displayName,String role,boolean emailVerified) {
    public static CurrentUserResponse from(Account account) { return new CurrentUserResponse(account.id(),account.email(),account.displayName(),account.role(),account.emailVerifiedAt()!=null); }
}
