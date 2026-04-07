package com.example.epub.infrastructure.identity;

import com.example.epub.application.identity.*;
import com.example.epub.domain.identity.Account;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public class JdbcAccountSecurity implements AccountSecurity {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccountStore accounts;
    private final AuditEvents audits;
    public JdbcAccountSecurity(JdbcTemplate jdbc,TransactionTemplate transactions,AccountStore accounts,AuditEvents audits) {
        this.jdbc=jdbc;this.transactions=transactions;this.accounts=accounts;this.audits=audits;
    }
    @Override public Account profile(UUID user,Instant epoch) {
        var account=accounts.byId(user).filter(Account::active).orElseThrow(() -> new AccessPolicy.Denied(false));
        if(!account.passwordChangedAt().equals(epoch))throw new AccessPolicy.Denied(false);return account;
    }
    private void lock(UUID id) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,2))",Object.class,id.toString());
        if(jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",UUID.class,id).isEmpty())throw new AccessPolicy.Denied(false);
    }
    @Override public Account updateProfile(UUID user,Instant epoch,String name,String bio) {
        return transactions.execute(tx -> {
            lock(user);profile(user,epoch);
            jdbc.update("UPDATE users SET display_name=coalesce(?,display_name),bio=coalesce(?,bio),revision=revision+1,updated_at=now() WHERE id=?",name,bio,user);
            return profile(user,epoch);
        });
    }
    @Override public Account changePassword(UUID user,Instant epoch,String expected,String encoded,String requestId) {
        return transactions.execute(tx -> {
            lock(user);var account=profile(user,epoch);
            if(account.passwordHash()==null || !account.passwordHash().equals(expected))throw new AccessPolicy.Denied(false);
            jdbc.update("UPDATE users SET password_hash=?,password_changed_at=clock_timestamp(),revision=revision+1,updated_at=now() WHERE id=?",encoded,user);
            jdbc.update("UPDATE auth_tokens SET consumed_at=clock_timestamp() WHERE user_id=? AND purpose='RESET_PASSWORD' AND consumed_at IS NULL",user);
            jdbc.update("DELETE FROM spring_session WHERE principal_name=?",user.toString());
            audits.record(AuditEvents.Action.PASSWORD_CHANGED,user,user,requestId);
            return accounts.byId(user).orElseThrow();
        });
    }
    @Override public List<String> providers(UUID user) {
        var result=new ArrayList<String>();
        if(accounts.byId(user).orElseThrow().passwordHash()!=null)result.add("PASSWORD");
        result.addAll(jdbc.queryForList("SELECT provider FROM user_identities WHERE user_id=? ORDER BY provider",String.class,user));
        return List.copyOf(result);
    }
    @Override public List<SessionInfo> sessions(UUID user,Instant epoch,String current) {
        profile(user,epoch);
        return jdbc.query("SELECT primary_id,session_id,creation_time,last_access_time FROM spring_session WHERE principal_name=? AND expiry_time>? ORDER BY last_access_time DESC,primary_id LIMIT 100",
            (row,index) -> new SessionInfo(UUID.fromString(row.getString("primary_id")),Instant.ofEpochMilli(row.getLong("creation_time")),
                Instant.ofEpochMilli(row.getLong("last_access_time")),current.equals(row.getString("session_id"))),user.toString(),System.currentTimeMillis());
    }
    @Override public boolean revoke(UUID user,Instant epoch,UUID target,String current) {
        return transactions.execute(tx -> {
            profile(user,epoch);
            var removed=jdbc.queryForList("DELETE FROM spring_session WHERE primary_id=? AND principal_name=? RETURNING session_id",String.class,target.toString(),user.toString());
            if(removed.isEmpty())throw new AccessPolicy.Denied(true);return current.equals(removed.getFirst());
        });
    }
}
