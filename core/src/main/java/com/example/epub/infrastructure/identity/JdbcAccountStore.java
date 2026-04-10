package com.example.epub.infrastructure.identity;

import com.example.epub.application.identity.AccountStore;
import com.example.epub.domain.identity.Account;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcAccountStore implements AccountStore {
    private final JdbcTemplate jdbc;
    public JdbcAccountStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private Account map(ResultSet rs, int row) throws SQLException {
        var verified=rs.getTimestamp("email_verified_at");
        return new Account(rs.getObject("id",UUID.class),rs.getString("email_normalized"),rs.getString("password_hash"),
                rs.getString("display_name"),rs.getString("bio"),rs.getString("role"),rs.getString("status"),
                verified==null?null:verified.toInstant(),rs.getTimestamp("password_changed_at").toInstant(),rs.getLong("revision"));
    }
    @Override public Optional<Account> byEmail(String email) {
        return jdbc.query("SELECT * FROM users WHERE email_normalized=?",this::map,email).stream().findFirst();
    }
    @Override public Optional<Account> byId(UUID id) {
        return jdbc.query("SELECT * FROM users WHERE id=?",this::map,id).stream().findFirst();
    }
    @Override public boolean allowAttempt(String key,int limit,Duration window) {
        Integer hits=jdbc.queryForObject("""
                INSERT INTO auth_rate_limits(bucket_key,window_started,hits) VALUES (?,clock_timestamp(),1)
                ON CONFLICT(bucket_key) DO UPDATE SET
                  hits=CASE WHEN auth_rate_limits.window_started < clock_timestamp() - (? * interval '1 second')
                    THEN 1 ELSE least(auth_rate_limits.hits+1,1000000) END,
                  window_started=CASE WHEN auth_rate_limits.window_started < clock_timestamp() - (? * interval '1 second')
                    THEN clock_timestamp() ELSE auth_rate_limits.window_started END
                RETURNING hits
                """,Integer.class,key,window.toSeconds(),window.toSeconds());
        // Expired buckets contain only one-way hashes. Reclaim them without a scheduler dependency.
        jdbc.update("DELETE FROM auth_rate_limits WHERE window_started < clock_timestamp() - interval '1 day'");
        return hits!=null && hits<=limit;
    }
}
