package com.example.epub.core.repository.identity;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
public class AuditRepository {
    private final JdbcTemplate jdbc;
    public AuditRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void insert(String action,UUID actor,UUID target,String requestId) { jdbc.update("INSERT INTO audit_logs(actor_id,action,target_type,target_id,request_id) VALUES (?,?,'USER',?,?)",actor,action,target,requestId); }
}
