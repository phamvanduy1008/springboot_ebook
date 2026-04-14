package com.example.epub.core.service.identity;
import com.example.epub.core.repository.identity.*;
import java.time.Duration;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;
public class RegistrationService implements Registration {
    private final RegistrationRepository registration;
    private final TransactionTemplate transactions;
    private final AccountRepository accounts;
    private final EmailTokenService tokens;
    public RegistrationService(RegistrationRepository registration,TransactionTemplate transactions,AccountRepository accounts,EmailTokenService tokens) {
        this.registration=registration;this.transactions=transactions;this.accounts=accounts;this.tokens=tokens;
    }
    @Override public void register(String email,String name,String encoded) {
        transactions.executeWithoutResult(tx -> {
            registration.lockEmail(email);
            UUID id=UUID.randomUUID();
            if(registration.create(id,email,encoded,name))tokens.send(id,email,"VERIFY_EMAIL","/verify-email",Duration.ofHours(1));
        });
    }
    @Override public void resend(String email) {
        transactions.executeWithoutResult(tx -> {
            registration.lockEmail(email);
            accounts.byEmail(email).filter(account -> account.active() && account.emailVerifiedAt()==null).ifPresent(
                account -> tokens.send(account.id(),account.email(),"VERIFY_EMAIL","/verify-email",Duration.ofHours(1)));
        });
    }
    @Override public void verify(String token) {
        transactions.executeWithoutResult(tx -> { if(!registration.verifyActive(tokens.consume(token,"VERIFY_EMAIL")))throw new InvalidToken(); });
    }
}
