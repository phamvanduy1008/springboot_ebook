package com.example.epub.infrastructure.identity;

import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@AutoConfiguration(after=IdentityConfiguration.class)
@Profile("local")
@ConditionalOnProperty(name="app.local-seed.enabled", havingValue="true")
public class LocalUserSeed {
    @Bean public ApplicationRunner seedLocalUser(JdbcTemplate jdbc, Environment env) {
        return args -> {
            String email=env.getProperty("SEED_EMAIL", "").trim().toLowerCase(Locale.ROOT);
            String password=env.getProperty("SEED_PASSWORD", "");
            if (!email.contains("@") || email.length()>320 || password.length()<12 || password.length()>72) {
                throw new IllegalStateException("Opt-in local seed requires SEED_EMAIL and SEED_PASSWORD (12..72 characters)");
            }
            jdbc.update("INSERT INTO users(id,email_normalized,password_hash,display_name) VALUES (?,?,?,?) ON CONFLICT(email_normalized) DO NOTHING",
                    UUID.randomUUID(), email, new BCryptPasswordEncoder(12).encode(password), "Local user");
        };
    }
}
