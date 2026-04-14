package com.example.epub.core.repository.identity;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
public class AccessRepository {
    private final JdbcTemplate jdbc;
    public AccessRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void lockAdministratorSet() { jdbc.execute("SELECT pg_advisory_xact_lock(729173041)"); }
    public long activeAdministrators() { return jdbc.queryForObject("SELECT count(*) FROM users WHERE role='ADMIN' AND status='ACTIVE'",Long.class); }
    public void change(UUID target,String role,String status) { jdbc.update("UPDATE users SET role=?,status=?,revision=revision+1,updated_at=now() WHERE id=?",role,status,target); }
}
