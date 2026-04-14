package com.example.epub.core.repository.identity;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
public class EmailTokenRepository {
    private final JdbcTemplate jdbc;
    public EmailTokenRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void replace(UUID user,String hash,String purpose,Duration ttl) {
        jdbc.update("UPDATE auth_tokens SET consumed_at=now() WHERE user_id=? AND purpose=? AND consumed_at IS NULL",user,purpose);
        jdbc.update("INSERT INTO auth_tokens(user_id,token_hash,purpose,expires_at) VALUES (?,?,?,now()+(? * interval '1 second'))",user,hash,purpose,ttl.toSeconds());
    }
    public Optional<UUID> consume(String hash,String purpose) {
        return jdbc.queryForList("""
                UPDATE auth_tokens SET consumed_at=clock_timestamp()
                WHERE token_hash=? AND purpose=? AND consumed_at IS NULL AND expires_at>clock_timestamp()
                RETURNING user_id
                """,UUID.class,hash,purpose).stream().findFirst();
    }
    public Optional<UUID> userFor(String hash,String purpose) {
        return jdbc.queryForList("SELECT user_id FROM auth_tokens WHERE token_hash=? AND purpose=? AND consumed_at IS NULL AND expires_at>clock_timestamp()",UUID.class,hash,purpose).stream().findFirst();
    }
}
