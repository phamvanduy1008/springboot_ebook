package com.example.epub.core.service.identity;
import com.example.epub.core.model.identity.Account;
import com.example.epub.core.repository.identity.AccountRepository;
import java.time.Duration;
import java.util.*;
/** Account reads and atomic attempt limits shared by security entry points. */
public class AccountService {
    private final AccountRepository accounts;
    public AccountService(AccountRepository accounts) { this.accounts=accounts; }
    public Optional<Account> byEmail(String email) { return accounts.byEmail(email); }
    public Optional<Account> byId(UUID id) { return accounts.byId(id); }
    public boolean allowAttempt(String key,int limit,Duration window) { return accounts.allowAttempt(key,limit,window); }
}
