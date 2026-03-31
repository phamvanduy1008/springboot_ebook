package com.example.epub.infrastructure.identity;

import com.example.epub.application.identity.*;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public class JdbcRegistration implements Registration {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccountStore accounts;
    private final EmailTokens tokens;
    public JdbcRegistration(JdbcTemplate jdbc,TransactionTemplate transactions,AccountStore accounts,EmailTokens tokens) {
        this.jdbc=jdbc;this.transactions=transactions;this.accounts=accounts;this.tokens=tokens;
    }
    private void lockEmail(String email) {jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,1))",Object.class,email);}
    @Override public void register(String email,String name,String encoded) {
        transactions.executeWithoutResult(tx -> {
            lockEmail(email);
            UUID id=UUID.randomUUID();
            int created=jdbc.update("INSERT INTO users(id,email_normalized,password_hash,display_name) VALUES (?,?,?,?) ON CONFLICT(email_normalized) DO NOTHING",id,email,encoded,name);
            if(created==1)tokens.send(id,email,"VERIFY_EMAIL","/verify-email",Duration.ofHours(1));
        });
    }
    @Override public void resend(String email) {
        transactions.executeWithoutResult(tx -> {
            lockEmail(email);
            accounts.byEmail(email).filter(account -> account.active() && account.emailVerifiedAt()==null).ifPresent(
                account -> tokens.send(account.id(),account.email(),"VERIFY_EMAIL","/verify-email",Duration.ofHours(1)));
        });
    }
    @Override public void verify(String token) {
        transactions.executeWithoutResult(tx -> {
            UUID id=tokens.consume(token,"VERIFY_EMAIL");
            int updated=jdbc.update("UPDATE users SET email_verified_at=coalesce(email_verified_at,clock_timestamp()),revision=revision+1,updated_at=now() WHERE id=? AND status='ACTIVE'",id);
            if(updated!=1)throw new InvalidToken();
        });
    }
}
