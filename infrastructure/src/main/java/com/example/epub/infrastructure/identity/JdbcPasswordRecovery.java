package com.example.epub.infrastructure.identity;

import com.example.epub.application.identity.*;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public class JdbcPasswordRecovery implements PasswordRecovery {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccountStore accounts;
    private final EmailTokens tokens;
    private final AuditEvents audits;
    public JdbcPasswordRecovery(JdbcTemplate jdbc,TransactionTemplate transactions,AccountStore accounts,EmailTokens tokens,AuditEvents audits) {
        this.jdbc=jdbc;this.transactions=transactions;this.accounts=accounts;this.tokens=tokens;this.audits=audits;
    }
    private void lock(UUID id) {jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,2))",Object.class,id.toString());}
    @Override public void forgot(String email) {
        transactions.executeWithoutResult(tx -> accounts.byEmail(email).filter(a -> a.active() && a.passwordHash()!=null).ifPresent(account -> {
            lock(account.id());
            tokens.send(account.id(),account.email(),"RESET_PASSWORD","/reset-password",Duration.ofMinutes(30));
        }));
    }
    @Override public void reset(String token,String encoded,String requestId) {
        transactions.executeWithoutResult(tx -> {
            UUID id=tokens.userFor(token,"RESET_PASSWORD");lock(id);
            var account=accounts.byId(id).filter(a -> a.active() && a.passwordHash()!=null).orElseThrow(Registration.InvalidToken::new);
            if(!tokens.consume(token,"RESET_PASSWORD").equals(account.id()))throw new Registration.InvalidToken();
            if(jdbc.update("UPDATE users SET password_hash=?,password_changed_at=clock_timestamp(),revision=revision+1,updated_at=now() WHERE id=? AND status='ACTIVE' AND password_hash IS NOT NULL",encoded,id)!=1)
                throw new Registration.InvalidToken();
            jdbc.update("UPDATE auth_tokens SET consumed_at=clock_timestamp() WHERE user_id=? AND purpose='RESET_PASSWORD' AND consumed_at IS NULL",id);
            jdbc.update("DELETE FROM spring_session WHERE principal_name=?",id.toString());
            audits.record(AuditEvents.Action.PASSWORD_RESET,id,id,requestId);
        });
    }
}
