package com.example.epub.api.security;

import com.example.epub.core.service.identity.AccessPolicy;
import com.example.epub.core.model.identity.Account;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccessPolicyTest {
    private Account account(UUID id,String role,String status,boolean verified) {
        return new Account(id,"reader@example.test","redacted","Reader","",role,status,verified?Instant.EPOCH:null,Instant.EPOCH,0);
    }
    @Test void ownershipMatrixNeverGrantsAdministratorsImplicitPrivateAccess() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        var owner=account(a,"USER","ACTIVE",true);var other=account(b,"USER","ACTIVE",true);
        var admin=account(b,"ADMIN","ACTIVE",true);
        assertTrue(AccessPolicy.canRead(null,null,true));
        assertTrue(AccessPolicy.canRead(owner,a,false));
        for(var actor:new Account[]{null,other,admin,account(a,"USER","LOCKED",true),account(a,"USER","DELETING",true)}) {
            assertFalse(AccessPolicy.canRead(actor,a,false));
            assertTrue(assertThrows(AccessPolicy.Denied.class,() -> AccessPolicy.requirePrivateOwner(actor,a)).hidden());
        }
        AccessPolicy.requireAdministrator(admin);
        assertThrows(AccessPolicy.Denied.class,() -> AccessPolicy.requireAdministrator(owner));
        assertThrows(AccessPolicy.Denied.class,() -> AccessPolicy.requireVerified(account(a,"USER","ACTIVE",false)));
        AccessPolicy.requireVerified(owner);
    }
}
