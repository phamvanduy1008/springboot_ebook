package com.example.epub.core.service.identity;
import com.example.epub.core.repository.identity.*;
import com.example.epub.core.model.identity.Account;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;
public class AccountSecurityService implements AccountSecurity {
    private final AccountMutationRepository changes;
    private final TransactionTemplate transactions;
    private final AccountRepository accounts;
    private final AuditEvents audits;
    public AccountSecurityService(AccountMutationRepository changes,TransactionTemplate transactions,AccountRepository accounts,AuditEvents audits) {
        this.changes=changes;this.transactions=transactions;this.accounts=accounts;this.audits=audits;
    }
    @Override public Account profile(UUID user,Instant epoch) {
        var account=accounts.byId(user).filter(Account::active).orElseThrow(() -> new AccessPolicy.Denied(false));
        if(!account.passwordChangedAt().equals(epoch))throw new AccessPolicy.Denied(false);return account;
    }
    private void lock(UUID id) {
        changes.lockCredentials(id);
        if(!changes.lockUserRow(id))throw new AccessPolicy.Denied(false);
    }
    @Override public Account updateProfile(UUID user,Instant epoch,String name,String bio) {
        return transactions.execute(tx -> { lock(user);profile(user,epoch);changes.updateProfile(user,name,bio);return profile(user,epoch); });
    }
    @Override public Account changePassword(UUID user,Instant epoch,String expected,String encoded,String requestId) {
        return transactions.execute(tx -> {
            lock(user);var account=profile(user,epoch);
            if(account.passwordHash()==null || !account.passwordHash().equals(expected))throw new AccessPolicy.Denied(false);
            changes.changePassword(user,encoded);changes.invalidateResetTokens(user);changes.revokeAll(user);
            audits.record(AuditEvents.Action.PASSWORD_CHANGED,user,user,requestId);
            return accounts.byId(user).orElseThrow();
        });
    }
    @Override public List<String> providers(UUID user) {
        var result=new ArrayList<String>();
        if(accounts.byId(user).orElseThrow().passwordHash()!=null)result.add("PASSWORD");
        result.addAll(changes.providers(user));return List.copyOf(result);
    }
    @Override public List<SessionInfo> sessions(UUID user,Instant epoch,String current) {
        profile(user,epoch);
        return changes.sessions(user,System.currentTimeMillis()).stream().map(row -> new SessionInfo(row.id(),row.createdAt(),row.lastAccessAt(),current.equals(row.sessionId()))).toList();
    }
    @Override public boolean revoke(UUID user,Instant epoch,UUID target,String current) {
        return transactions.execute(tx -> { profile(user,epoch);return current.equals(changes.revoke(user,target).orElseThrow(() -> new AccessPolicy.Denied(true))); });
    }
}
