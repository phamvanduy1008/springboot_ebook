package com.example.epub.core.service.identity;
import com.example.epub.core.repository.identity.*;
import java.time.Duration;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;
public class PasswordRecoveryService implements PasswordRecovery {
    private final AccountMutationRepository changes;
    private final TransactionTemplate transactions;
    private final AccountRepository accounts;
    private final EmailTokenService tokens;
    private final AuditEvents audits;
    public PasswordRecoveryService(AccountMutationRepository changes,TransactionTemplate transactions,AccountRepository accounts,EmailTokenService tokens,AuditEvents audits) {
        this.changes=changes;this.transactions=transactions;this.accounts=accounts;this.tokens=tokens;this.audits=audits;
    }
    @Override public void forgot(String email) {
        transactions.executeWithoutResult(tx -> accounts.byEmail(email).filter(a -> a.active() && a.passwordHash()!=null).ifPresent(account -> {
            changes.lockCredentials(account.id());tokens.send(account.id(),account.email(),"RESET_PASSWORD","/reset-password",Duration.ofMinutes(30));
        }));
    }
    @Override public void reset(String token,String encoded,String requestId) {
        transactions.executeWithoutResult(tx -> {
            UUID id=tokens.userFor(token,"RESET_PASSWORD");changes.lockCredentials(id);
            var account=accounts.byId(id).filter(a -> a.active() && a.passwordHash()!=null).orElseThrow(Registration.InvalidToken::new);
            if(!tokens.consume(token,"RESET_PASSWORD").equals(account.id()))throw new Registration.InvalidToken();
            if(!changes.resetActivePassword(id,encoded))throw new Registration.InvalidToken();
            changes.invalidateResetTokens(id);changes.revokeAll(id);audits.record(AuditEvents.Action.PASSWORD_RESET,id,id,requestId);
        });
    }
}
