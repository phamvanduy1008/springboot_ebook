package com.example.epub.api.auth;

import com.example.epub.core.repository.identity.AccountRepository;

import com.example.epub.api.ApiApplication;
import com.example.epub.core.service.identity.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="EPUB_IDENTITY_DB_TEST",matches="true")
@EnabledIfEnvironmentVariable(named="EPUB_MAILPIT_URL",matches="http://.*")
class PasswordRecoveryIT {
    @Test void resetConsumesOnceRevokesEverySessionAndNeverCreatesAGooglePassword() throws Exception {
        try(var context=new SpringApplicationBuilder(ApiApplication.class).run("--spring.profiles.active=local","--server.port=0",
                "--app.local-seed.enabled=false","--logging.level.root=WARN","--debug=false");
            var first=new AuthSessionIT.Browser();var second=new AuthSessionIT.Browser();var guest=new AuthSessionIT.Browser()) {
            int port=((WebServerApplicationContext)context).getWebServer().getPort();first.port=port;second.port=port;guest.port=port;
            first.csrf();second.csrf();guest.csrf();
            var jdbc=context.getBean(JdbcTemplate.class);var accounts=context.getBean(AccountRepository.class);var recovery=context.getBean(PasswordRecovery.class);
            UUID id=UUID.randomUUID();String email="reset-"+id+"@example.test",oldPassword="Previous password 123",newPassword="Replacement password 456";
            String encoded=new BCryptPasswordEncoder(12).encode(oldPassword);
            jdbc.update("INSERT INTO users(id,email_normalized,password_hash,display_name) VALUES (?,?,?,'Reader')",id,email,encoded);
            String login="{\"email\":\""+email+"\",\"password\":\""+oldPassword+"\"}";
            assertEquals(200,first.call("POST","/api/v1/auth/login",login,true).statusCode());
            assertEquals(200,second.call("POST","/api/v1/auth/login",login,true).statusCode());
            var forgot=guest.call("POST","/api/v1/auth/forgot-password","{\"email\":\""+email+"\"}",true);
            assertEquals(202,forgot.statusCode());
            var unknown=guest.call("POST","/api/v1/auth/forgot-password","{\"email\":\""+UUID.randomUUID()+"@example.test\"}",true);
            assertEquals(forgot.statusCode(),unknown.statusCode());assertEquals(forgot.body(),unknown.body());
            String token=RegistrationIT.tokenFromMail(email,"Reset your password");
            assertThrows(Registration.InvalidToken.class,() -> context.getBean(Registration.class).verify(token));
            assertEquals(403,guest.call("POST","/api/v1/auth/reset-password","{\"token\":\""+token+"\",\"password\":\""+newPassword+"\"}",false).statusCode());
            assertEquals(204,guest.call("POST","/api/v1/auth/reset-password","{\"token\":\""+token+"\",\"password\":\""+newPassword+"\"}",true).statusCode());
            assertEquals(401,first.call("GET","/api/v1/auth/me",null,false).statusCode());assertEquals(401,second.call("GET","/api/v1/auth/me",null,false).statusCode());
            assertEquals(400,guest.call("POST","/api/v1/auth/reset-password","{\"token\":\""+token+"\",\"password\":\""+newPassword+"\"}",true).statusCode());
            guest.csrf();assertEquals(401,guest.call("POST","/api/v1/auth/login",login,true).statusCode());
            assertEquals(200,guest.call("POST","/api/v1/auth/login",login.replace(oldPassword,newPassword),true).statusCode());
            assertTrue(new BCryptPasswordEncoder().matches(newPassword,accounts.byId(id).orElseThrow().passwordHash()));
            recovery.forgot(email);String concurrent=RegistrationIT.tokenFromMail(email,"Reset your password");
            try(var executor=Executors.newFixedThreadPool(2)) {
                Callable<Boolean> reset=() -> {try{recovery.reset(concurrent,encoded,"reset-race");return true;}catch(Registration.InvalidToken expected){return false;}};
                var result=executor.invokeAll(List.of(reset,reset));assertEquals(1,result.stream().filter(f -> {try{return f.get();}catch(Exception e){throw new AssertionError(e);}}).count());
            }
            recovery.forgot(email);String expired=RegistrationIT.tokenFromMail(email,"Reset your password");
            jdbc.update("UPDATE auth_tokens SET created_at=now()-interval '2 hours',expires_at=now()-interval '1 hour' WHERE token_hash=?",AuthController.hash(expired));
            assertThrows(Registration.InvalidToken.class,() -> recovery.reset(expired,encoded,"expired-test"));
            var google=context.getBean(GoogleAccounts.class).login("https://accounts.google.com",UUID.randomUUID().toString(),UUID.randomUUID()+"@gmail.com",true,true,"Google");
            guest.csrf();var googleForgot=guest.call("POST","/api/v1/auth/forgot-password","{\"email\":\""+google.email()+"\"}",true);
            assertEquals(forgot.body(),googleForgot.body());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM auth_tokens WHERE user_id=? AND purpose='RESET_PASSWORD'",Integer.class,google.id()));
            assertNull(accounts.byId(google.id()).orElseThrow().passwordHash());
            String audit=jdbc.queryForObject("SELECT string_agg(safe_metadata::text,',') FROM audit_logs WHERE action='PASSWORD_RESET'",String.class);
            assertNotNull(audit);assertFalse(audit.contains(token));assertFalse(audit.contains(newPassword));
            jdbc.update("DELETE FROM users WHERE id IN (?,?)",id,google.id());
        }
    }
}
