package com.example.epub.api.auth;

import com.example.epub.api.ApiApplication;
import com.example.epub.core.service.identity.*;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="EPUB_IDENTITY_DB_TEST",matches="true")
class AccessPolicyIT {
    @Test void existingSessionsLoseAccessAndConcurrentDemotionProtectsLastAdmin() throws Exception {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        try(var context=new SpringApplicationBuilder(ApiApplication.class).run("--spring.profiles.active=local","--server.port=0",
                "--app.local-seed.enabled=false","--logging.level.root=WARN","--debug=false");var browser=new AuthSessionIT.Browser()) {
            var jdbc=context.getBean(JdbcTemplate.class);var changes=context.getBean(AccessManagement.class);
            String password="Access policy password 123";String email="access-"+a+"@example.test";
            for(UUID id:List.of(a,b))jdbc.update("INSERT INTO users(id,email_normalized,password_hash,display_name,role) VALUES (?,?,?,'Admin','ADMIN')",
                    id,id.equals(a)?email:"access-"+b+"@example.test",new BCryptPasswordEncoder(12).encode(password));
            browser.port=((WebServerApplicationContext)context).getWebServer().getPort();browser.csrf();
            assertEquals(200,browser.call("POST","/api/v1/auth/login","{\"email\":\""+email+"\",\"password\":\""+password+"\"}",true).statusCode());
            jdbc.update("UPDATE users SET role='USER' WHERE id=?",a);
            var me=browser.call("GET","/api/v1/auth/me",null,false);assertEquals(200,me.statusCode());assertTrue(me.body().contains("USER"));
            jdbc.update("UPDATE users SET status='LOCKED' WHERE id=?",a);
            assertEquals(401,browser.call("GET","/api/v1/auth/me",null,false).statusCode());
            jdbc.update("UPDATE users SET role='ADMIN',status='ACTIVE' WHERE id=?",a);
            try(var executor=Executors.newFixedThreadPool(2)) {
                var jobs=executor.invokeAll(List.<Callable<Boolean>>of(
                    () -> demote(changes,a,b), () -> demote(changes,b,a)));
                assertEquals(1,jobs.stream().filter(job -> {try{return job.get();}catch(Exception e){throw new AssertionError(e);}}).count());
            }
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM users WHERE role='ADMIN' AND status='ACTIVE'",Integer.class));
            UUID last=jdbc.queryForObject("SELECT id FROM users WHERE role='ADMIN' AND status='ACTIVE'",UUID.class);
            assertThrows(AccessPolicy.Denied.class,() -> changes.change(last,last,"USER","ACTIVE","last-admin-test"));
            String audit=jdbc.queryForObject("SELECT string_agg(action || request_id || safe_metadata::text,',') FROM audit_logs",String.class);
            assertTrue(audit.contains("ACCESS_CHANGED"));assertFalse(audit.contains(password));assertFalse(audit.contains(email));
            browser.csrf();
            assertEquals(200,browser.call("POST","/api/v1/auth/login","{\"email\":\""+email+"\",\"password\":\""+password+"\"}",true).statusCode());
            jdbc.update("UPDATE users SET password_changed_at=clock_timestamp() WHERE id=?",a);
            assertEquals(401,browser.call("GET","/api/v1/auth/me",null,false).statusCode(),"Credential epoch revokes even sessions racing a password update");
            jdbc.update("DELETE FROM users WHERE id IN (?,?)",a,b);
        }
    }
    private boolean demote(AccessManagement changes,UUID actor,UUID target) {
        try {changes.change(actor,target,"USER","ACTIVE","race-test");return true;}
        catch(AccessPolicy.Denied expected) {return false;}
    }
}
