package com.example.epub.core.service.identity;
import com.example.epub.core.repository.identity.*;
import com.example.epub.core.model.identity.Account;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
public class GoogleAccountService implements GoogleAccounts {
    private static final String ISSUER="https://accounts.google.com";
    private final GoogleIdentityRepository identities;
    private final AccountMutationRepository changes;
    private final TransactionTemplate transactions;
    private final AccountRepository accounts;
    public GoogleAccountService(GoogleIdentityRepository identities,AccountMutationRepository changes,TransactionTemplate transactions,AccountRepository accounts) {
        this.identities=identities;this.changes=changes;this.transactions=transactions;this.accounts=accounts;
    }
    private static void validate(String issuer,String subject) {
        if(!Set.of(ISSUER,"accounts.google.com").contains(issuer) || subject==null || subject.isBlank() || subject.length()>255)throw new Rejected();
    }
    @Override public Account login(String issuer,String subject,String email,boolean verified,boolean authoritative,String name) {
        validate(issuer,subject);
        try {
            return transactions.execute(tx -> {
                identities.lockIdentity(ISSUER,subject);
                var existing=identities.owner(ISSUER,subject);
                if(existing.isPresent())return accounts.byId(existing.get()).filter(Account::active).orElseThrow(Rejected::new);
                if(email==null || !verified)throw new Rejected();
                String normalized=email.trim().toLowerCase(Locale.ROOT);
                if(normalized.length()>320 || !normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || accounts.byEmail(normalized).isPresent())throw new Rejected();
                String display=name==null?"Reader":name.replaceAll("[\\p{Cntrl}]","").strip();
                if(display.isEmpty())display="Reader";
                if(display.codePointCount(0,display.length())>60)display=display.substring(0,display.offsetByCodePoints(0,60));
                UUID id=UUID.randomUUID();identities.createUser(id,normalized,display,authoritative?Instant.now():null);identities.link(id,ISSUER,subject);
                return accounts.byId(id).orElseThrow();
            });
        } catch(DataIntegrityViolationException collision) {throw new Rejected();}
    }
    @Override public Account link(UUID user,Instant epoch,String issuer,String subject) {
        validate(issuer,subject);
        try {
            return transactions.execute(tx -> {
                identities.lockIdentity(ISSUER,subject);changes.lockCredentials(user);
                if(!changes.lockUserRow(user))throw new Rejected();
                var account=accounts.byId(user).filter(Account::active).orElseThrow(Rejected::new);
                if(account.passwordHash()==null || !account.passwordChangedAt().equals(epoch))throw new Rejected();
                var owner=identities.owner(ISSUER,subject);
                if(owner.isPresent()) {if(owner.get().equals(user))return account;throw new Rejected();}
                identities.link(user,ISSUER,subject);return account;
            });
        } catch(DataIntegrityViolationException conflict) {throw new Rejected();}
    }
}
