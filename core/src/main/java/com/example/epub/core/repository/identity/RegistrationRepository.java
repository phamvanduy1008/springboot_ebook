package com.example.epub.core.repository.identity;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
public class RegistrationRepository {
    private final JdbcTemplate jdbc;
    public RegistrationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void lockEmail(String email) { jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,1))",Object.class,email); }
    public boolean create(UUID id,String email,String encoded,String name) {
        return jdbc.update("INSERT INTO users(id,email_normalized,password_hash,display_name) VALUES (?,?,?,?) ON CONFLICT(email_normalized) DO NOTHING",id,email,encoded,name)==1;
    }
    public boolean verifyActive(UUID id) {
        return jdbc.update("UPDATE users SET email_verified_at=coalesce(email_verified_at,clock_timestamp()),revision=revision+1,updated_at=now() WHERE id=? AND status='ACTIVE'",id)==1;
    }
}
