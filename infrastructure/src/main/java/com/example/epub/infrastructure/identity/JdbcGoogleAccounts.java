package com.example.epub.infrastructure.identity;

import com.example.epub.application.identity.*;
import com.example.epub.domain.identity.Account;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public class JdbcGoogleAccounts implements GoogleAccounts {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccountStore accounts;
    public JdbcGoogleAccounts(JdbcTemplate jdbc,TransactionTemplate transactions,AccountStore accounts) {
        this.jdbc=jdbc;this.transactions=transactions;this.accounts=accounts;
    }
    @Override public Account login(String issuer,String subject,String email,boolean verified,boolean authoritative,String name) {
        if(!Set.of("https://accounts.google.com","accounts.google.com").contains(issuer)
                || subject==null || subject.isBlank() || subject.length()>255) throw new Rejected();
        final String canonical="https://accounts.google.com";
        try {
            return transactions.execute(tx -> {
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,canonical+":"+subject);
                var existing=jdbc.queryForList("SELECT user_id FROM user_identities WHERE provider='GOOGLE' AND issuer=? AND subject=?",UUID.class,canonical,subject);
                if(!existing.isEmpty()) return accounts.byId(existing.getFirst()).filter(Account::active).orElseThrow(Rejected::new);
                if(email==null || !verified)throw new Rejected();
                String normalized=email.trim().toLowerCase(Locale.ROOT);
                if(normalized.length()>320 || !normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || accounts.byEmail(normalized).isPresent())throw new Rejected();
                String display=name==null?"Reader":name.replaceAll("[\\p{Cntrl}]","").strip();
                if(display.isEmpty())display="Reader";
                if(display.codePointCount(0,display.length())>60)display=display.substring(0,display.offsetByCodePoints(0,60));
                UUID id=UUID.randomUUID();
                jdbc.update("INSERT INTO users(id,email_normalized,password_hash,display_name,email_verified_at) VALUES (?,?,NULL,?,?)",id,normalized,display,authoritative?Timestamp.from(Instant.now()):null);
                jdbc.update("INSERT INTO user_identities(user_id,provider,issuer,subject) VALUES (?,'GOOGLE',?,?)",id,canonical,subject);
                return accounts.byId(id).orElseThrow();
            });
        } catch(DataIntegrityViolationException collision) {throw new Rejected();}
    }
    @Override public Account link(UUID user,Instant epoch,String issuer,String subject) {
        if(!Set.of("https://accounts.google.com","accounts.google.com").contains(issuer) || subject==null || subject.isBlank() || subject.length()>255)throw new Rejected();
        try {
            return transactions.execute(tx -> {
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"https://accounts.google.com:"+subject);
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,2))",Object.class,user.toString());
                if(jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",UUID.class,user).isEmpty())throw new Rejected();
                var account=accounts.byId(user).filter(Account::active).orElseThrow(Rejected::new);
                if(account.passwordHash()==null || !account.passwordChangedAt().equals(epoch))throw new Rejected();
                var owner=jdbc.queryForList("SELECT user_id FROM user_identities WHERE provider='GOOGLE' AND issuer='https://accounts.google.com' AND subject=?",UUID.class,subject);
                if(!owner.isEmpty()) {if(owner.getFirst().equals(user))return account;throw new Rejected();}
                jdbc.update("INSERT INTO user_identities(user_id,provider,issuer,subject) VALUES (?,'GOOGLE','https://accounts.google.com',?)",user,subject);
                return account;
            });
        } catch(DataIntegrityViolationException conflict) {throw new Rejected();}
    }
}
