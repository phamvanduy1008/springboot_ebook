package com.example.epub.infrastructure.identity;

import com.example.epub.application.identity.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public class JdbcAccessChanges implements AccessChanges,AuditEvents {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccountStore accounts;
    public JdbcAccessChanges(JdbcTemplate jdbc,TransactionTemplate transactions,AccountStore accounts) {
        this.jdbc=jdbc;this.transactions=transactions;this.accounts=accounts;
    }
    @Override public void record(Action action,UUID actor,UUID target,String requestId) {
        String safe=requestId!=null && requestId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")?requestId:UUID.randomUUID().toString();
        jdbc.update("INSERT INTO audit_logs(actor_id,action,target_type,target_id,request_id) VALUES (?,?,'USER',?,?)",actor,action.name(),target,safe);
    }
    @Override public void change(UUID actor,UUID target,String role,String status,String requestId) {
        transactions.executeWithoutResult(tx -> {
            // Serialize changes to the administrator set, including concurrent demotions of different users.
            jdbc.execute("SELECT pg_advisory_xact_lock(729173041)");
            AccessPolicy.requireAdministrator(accounts.byId(actor).orElse(null));
            var user=accounts.byId(target).orElseThrow(() -> new AccessPolicy.Denied(true));
            if("ADMIN".equals(user.role()) && user.active() && (!"ADMIN".equals(role) || !"ACTIVE".equals(status))
                    && jdbc.queryForObject("SELECT count(*) FROM users WHERE role='ADMIN' AND status='ACTIVE'",Long.class)<=1)
                throw new AccessPolicy.Denied(false);
            jdbc.update("UPDATE users SET role=?,status=?,revision=revision+1,updated_at=now() WHERE id=?",role,status,target);
            jdbc.update("DELETE FROM spring_session WHERE principal_name=?",target.toString());
            record(Action.ACCESS_CHANGED,actor,target,requestId);
        });
    }
}
