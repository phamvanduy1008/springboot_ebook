package com.example.epub.core.service.identity;

import com.example.epub.core.repository.identity.AccountRepository;

import java.util.Set;
import java.util.UUID;

public final class AccessManagement {
    private final AccountRepository accounts;
    private final AccessChanges changes;
    public AccessManagement(AccountRepository accounts,AccessChanges changes) {this.accounts=accounts;this.changes=changes;}
    public void change(UUID actor,UUID target,String role,String status,String requestId) {
        if(!Set.of("USER","ADMIN").contains(role) || !Set.of("ACTIVE","LOCKED","DELETING","DELETED").contains(status))
            throw new IllegalArgumentException("Invalid access state");
        AccessPolicy.requireAdministrator(accounts.byId(actor).orElse(null));
        changes.change(actor,target,role,status,requestId);
    }
}
