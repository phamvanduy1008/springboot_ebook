package com.example.epub.core.repository.identity;
import com.example.epub.core.model.identity.StoredSession;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
public class AccountMutationRepository {
    private final JdbcTemplate jdbc;
    public AccountMutationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void lockCredentials(UUID id) { jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,2))",Object.class,id.toString()); }
    public boolean lockUserRow(UUID id) { return !jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",UUID.class,id).isEmpty(); }
    public void updateProfile(UUID user,String name,String bio) {
        jdbc.update("UPDATE users SET display_name=coalesce(?,display_name),bio=coalesce(?,bio),revision=revision+1,updated_at=now() WHERE id=?",name,bio,user);
    }
    public void changePassword(UUID user,String encoded) {
        jdbc.update("UPDATE users SET password_hash=?,password_changed_at=clock_timestamp(),revision=revision+1,updated_at=now() WHERE id=?",encoded,user);
    }
    public boolean resetActivePassword(UUID user,String encoded) {
        return jdbc.update("UPDATE users SET password_hash=?,password_changed_at=clock_timestamp(),revision=revision+1,updated_at=now() WHERE id=? AND status='ACTIVE' AND password_hash IS NOT NULL",encoded,user)==1;
    }
    public void invalidateResetTokens(UUID user) { jdbc.update("UPDATE auth_tokens SET consumed_at=clock_timestamp() WHERE user_id=? AND purpose='RESET_PASSWORD' AND consumed_at IS NULL",user); }
    public void revokeAll(UUID user) { jdbc.update("DELETE FROM spring_session WHERE principal_name=?",user.toString()); }
    public List<String> providers(UUID user) { return jdbc.queryForList("SELECT provider FROM user_identities WHERE user_id=? ORDER BY provider",String.class,user); }
    public List<StoredSession> sessions(UUID user,long now) {
        return jdbc.query("SELECT primary_id,session_id,creation_time,last_access_time FROM spring_session WHERE principal_name=? AND expiry_time>? ORDER BY last_access_time DESC,primary_id LIMIT 100",
            (row,index) -> new StoredSession(UUID.fromString(row.getString("primary_id")),row.getString("session_id"),Instant.ofEpochMilli(row.getLong("creation_time")),Instant.ofEpochMilli(row.getLong("last_access_time"))),user.toString(),now);
    }
    public Optional<String> revoke(UUID user,UUID target) {
        return jdbc.queryForList("DELETE FROM spring_session WHERE primary_id=? AND principal_name=? RETURNING session_id",String.class,target.toString(),user.toString()).stream().findFirst();
    }
}
