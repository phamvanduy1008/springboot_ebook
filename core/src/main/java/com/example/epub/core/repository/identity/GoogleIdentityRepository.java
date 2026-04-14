package com.example.epub.core.repository.identity;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
public class GoogleIdentityRepository {
    private final JdbcTemplate jdbc;
    public GoogleIdentityRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void lockIdentity(String issuer,String subject) { jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,issuer+":"+subject); }
    public Optional<UUID> owner(String issuer,String subject) {
        return jdbc.queryForList("SELECT user_id FROM user_identities WHERE provider='GOOGLE' AND issuer=? AND subject=?",UUID.class,issuer,subject).stream().findFirst();
    }
    public void createUser(UUID id,String email,String name,Instant verifiedAt) {
        jdbc.update("INSERT INTO users(id,email_normalized,password_hash,display_name,email_verified_at) VALUES (?,?,NULL,?,?)",id,email,name,verifiedAt==null?null:Timestamp.from(verifiedAt));
    }
    public void link(UUID user,String issuer,String subject) { jdbc.update("INSERT INTO user_identities(user_id,provider,issuer,subject) VALUES (?,'GOOGLE',?,?)",user,issuer,subject); }
}
