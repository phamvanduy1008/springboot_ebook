package com.example.epub.application.identity;

import com.example.epub.domain.identity.Account;
import java.util.UUID;

/** Use-case policies, independent of HTTP, persistence and client-supplied roles. */
public final class AccessPolicy {
    private AccessPolicy() { }
    public static boolean active(Account actor) { return actor!=null && actor.active(); }
    public static void requireAdministrator(Account actor) {
        if(!active(actor) || !"ADMIN".equals(actor.role())) throw new Denied(false);
    }
    public static void requireVerified(Account actor) {
        if(!active(actor) || actor.emailVerifiedAt()==null) throw new Denied(false);
    }
    public static void requirePrivateOwner(Account actor,UUID owner) {
        if(!active(actor) || owner==null || !owner.equals(actor.id())) throw new Denied(true);
    }
    public static boolean canRead(Account actor,UUID owner,boolean publicReady) {
        return publicReady || (active(actor) && owner!=null && owner.equals(actor.id()));
    }
    public static final class Denied extends RuntimeException {
        private final boolean hidden;
        public Denied(boolean hidden) { super("Access denied");this.hidden=hidden; }
        public boolean hidden() { return hidden; }
    }
}
