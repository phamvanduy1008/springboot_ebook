package com.example.epub.core.service.identity;
import com.example.epub.core.repository.identity.*;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;
public class AccessChangeService implements AccessChanges,AuditEvents {
    private final AccessRepository access;
    private final AuditRepository audits;
    private final AccountMutationRepository changes;
    private final TransactionTemplate transactions;
    private final AccountRepository accounts;
    public AccessChangeService(AccessRepository access,AuditRepository audits,AccountMutationRepository changes,TransactionTemplate transactions,AccountRepository accounts) {
        this.access=access;this.audits=audits;this.changes=changes;this.transactions=transactions;this.accounts=accounts;
    }
    @Override public void record(Action action,UUID actor,UUID target,String requestId) {
        String safe=requestId!=null && requestId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")?requestId:UUID.randomUUID().toString();
        audits.insert(action.name(),actor,target,safe);
    }
    @Override public void change(UUID actor,UUID target,String role,String status,String requestId) {
        transactions.executeWithoutResult(tx -> {
            access.lockAdministratorSet();AccessPolicy.requireAdministrator(accounts.byId(actor).orElse(null));
            var user=accounts.byId(target).orElseThrow(() -> new AccessPolicy.Denied(true));
            if("ADMIN".equals(user.role()) && user.active() && (!"ADMIN".equals(role) || !"ACTIVE".equals(status)) && access.activeAdministrators()<=1)throw new AccessPolicy.Denied(false);
            access.change(target,role,status);changes.revokeAll(target);record(Action.ACCESS_CHANGED,actor,target,requestId);
        });
    }
}
