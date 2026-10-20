package com.example.epub.core.config;

import com.example.epub.core.model.identity.Account;
import com.example.epub.core.repository.identity.AccountRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Opt-in first-run administrator for the local profile only. Never rotates existing credentials. */
@AutoConfiguration(after=IdentityConfiguration.class)
@Profile("local")
@ConditionalOnProperty(name="app.local-admin-seed.enabled",havingValue="true")
public class LocalAdminSeed {
    @Bean public ApplicationRunner seedLocalAdmin(AccountRepository accounts,JdbcTemplate jdbc,Environment env) {
        return args -> {
            String url=env.getProperty("app.database-url","");
            String host=url.startsWith("jdbc:postgresql://")?URI.create(url.substring(5)).getHost():null;
            if(host==null||!Set.of("localhost","127.0.0.1").contains(host))
                throw new IllegalStateException("Local admin seed requires a loopback PostgreSQL database");
            String email=env.getProperty("ADMIN_SEED_EMAIL","").trim().toLowerCase(Locale.ROOT);
            String password=env.getProperty("ADMIN_SEED_PASSWORD","");
            int bytes=password.getBytes(StandardCharsets.UTF_8).length;
            if(!email.matches("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}")||email.length()>320||bytes<12||bytes>72)
                throw new IllegalStateException("Local admin seed requires a valid ADMIN_SEED_EMAIL and 12..72 byte ADMIN_SEED_PASSWORD");
            var current=accounts.byEmail(email);
            if(current.isPresent()) {requireActiveAdministrator(current.get());return;}
            jdbc.update("INSERT INTO users(id,email_normalized,password_hash,display_name,role,status,email_verified_at) VALUES (?,?,?,'Local Administrator','ADMIN','ACTIVE',clock_timestamp()) ON CONFLICT(email_normalized) DO NOTHING",
                UUID.randomUUID(),email,new BCryptPasswordEncoder(12).encode(password));
            requireActiveAdministrator(accounts.byEmail(email).orElseThrow(()->new IllegalStateException("Local admin seed was not created")));
        };
    }
    private static void requireActiveAdministrator(Account account) {
        if(!account.active()||!"ADMIN".equals(account.role())||account.emailVerifiedAt()==null)
            throw new IllegalStateException("Local admin seed email already belongs to a non-admin or inactive account");
    }
}
