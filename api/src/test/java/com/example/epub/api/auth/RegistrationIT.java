package com.example.epub.api.auth;

import com.example.epub.api.ApiApplication;
import com.example.epub.application.identity.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="EPUB_IDENTITY_DB_TEST",matches="true")
@EnabledIfEnvironmentVariable(named="EPUB_MAILPIT_URL",matches="http://.*")
class RegistrationIT {
    static String tokenFromMail(String email,String subject) throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            String base=System.getenv("EPUB_MAILPIT_URL");
            var listing=client.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/messages?limit=100")).build(),HttpResponse.BodyHandlers.ofString());
            var messages=JsonMapper.builder().build().readTree(listing.body()).get("messages");
            for(var message:messages) {
                if(message.get("Subject").asText().equals(subject) && message.get("To").toString().contains(email)) {
                    var full=client.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/message/"+message.get("ID").asText())).build(),HttpResponse.BodyHandlers.ofString());
                    String text=JsonMapper.builder().build().readTree(full.body()).get("Text").asText();
                    var matcher=Pattern.compile("token=([A-Za-z0-9_-]{43})").matcher(text);assertTrue(matcher.find(),"Mail must contain a single-use link");
                    return matcher.group(1);
                }
            }
            throw new AssertionError("Expected recipient email was not captured by Mailpit");
        }
    }
    @Test void registrationVerificationResendAndTokenRacesWorkThroughMailpit() throws Exception {
        try(var context=new SpringApplicationBuilder(ApiApplication.class).run("--spring.profiles.active=local","--server.port=0",
                "--app.local-seed.enabled=false","--logging.level.root=WARN","--debug=false");var browser=new AuthSessionIT.Browser()) {
            browser.port=((WebServerApplicationContext)context).getWebServer().getPort();browser.csrf();
            var jdbc=context.getBean(JdbcTemplate.class);var accounts=context.getBean(AccountStore.class);var registration=context.getBean(Registration.class);
            String email="register-"+UUID.randomUUID()+"@example.test",password="Registration password 123";
            String input="{\"email\":\""+email.toUpperCase(Locale.ROOT)+"\",\"displayName\":\"Reader\",\"password\":\""+password+"\",\"role\":\"ADMIN\",\"emailVerified\":true}";
            assertEquals(403,browser.call("POST","/api/v1/auth/register",input,false).statusCode());
            var accepted=browser.call("POST","/api/v1/auth/register",input,true);assertEquals(202,accepted.statusCode(),accepted.body());
            var account=accounts.byEmail(email).orElseThrow();assertEquals("USER",account.role());assertNull(account.emailVerifiedAt());
            assertTrue(new BCryptPasswordEncoder().matches(password,account.passwordHash()));
            assertThrows(AccessPolicy.Denied.class,() -> AccessPolicy.requireVerified(account));
            String first=tokenFromMail(email,"Verify your email");
            assertFalse(jdbc.queryForObject("SELECT token_hash FROM auth_tokens WHERE user_id=? AND consumed_at IS NULL",String.class,account.id()).equals(first));
            assertEquals(accepted.body(),browser.call("POST","/api/v1/auth/register",input,true).body());
            assertEquals(202,browser.call("POST","/api/v1/auth/resend-verification","{\"email\":\""+email+"\"}",true).statusCode());
            String second=tokenFromMail(email,"Verify your email");assertNotEquals(first,second);
            assertEquals(400,browser.call("POST","/api/v1/auth/verify-email","{\"token\":\""+first+"\"}",true).statusCode());
            try(var executor=Executors.newFixedThreadPool(2)) {
                Callable<Boolean> consume=() -> {try{registration.verify(second);return true;}catch(Registration.InvalidToken expected){return false;}};
                var result=executor.invokeAll(List.of(consume,consume));assertEquals(1,result.stream().filter(f -> {try{return f.get();}catch(Exception e){throw new AssertionError(e);}}).count());
            }
            assertNotNull(accounts.byId(account.id()).orElseThrow().emailVerifiedAt());
            assertEquals(400,browser.call("POST","/api/v1/auth/verify-email","{\"token\":\""+second+"\"}",true).statusCode());
            String invalid="A".repeat(43);assertEquals(400,browser.call("POST","/api/v1/auth/verify-email","{\"token\":\""+invalid+"\"}",true).statusCode());
            String wrongPurpose="B".repeat(43);
            jdbc.update("INSERT INTO auth_tokens(user_id,token_hash,purpose,expires_at) VALUES (?,?,'RESET_PASSWORD',now()+interval '1 hour')",account.id(),AuthController.hash(wrongPurpose));
            assertThrows(Registration.InvalidToken.class,() -> registration.verify(wrongPurpose));
            String expired="C".repeat(43);
            jdbc.update("INSERT INTO auth_tokens(user_id,token_hash,purpose,created_at,expires_at) VALUES (?,?,'VERIFY_EMAIL',now()-interval '2 hours',now()-interval '1 hour')",account.id(),AuthController.hash(expired));
            assertThrows(Registration.InvalidToken.class,() -> registration.verify(expired));
            var google=context.getBean(GoogleAccounts.class).login("https://accounts.google.com",UUID.randomUUID().toString(),UUID.randomUUID()+"@example.test",true,false,"Google");
            registration.resend(google.email());registration.verify(tokenFromMail(google.email(),"Verify your email"));
            assertNotNull(accounts.byId(google.id()).orElseThrow().emailVerifiedAt());assertNull(accounts.byId(google.id()).orElseThrow().passwordHash());
            registration.register(google.email(),"Attempted takeover",account.passwordHash());assertNull(accounts.byId(google.id()).orElseThrow().passwordHash());
            String raceEmail=UUID.randomUUID()+"@example.test";
            try(var executor=Executors.newFixedThreadPool(2)) {
                var tasks=executor.invokeAll(List.<Callable<Void>>of(() -> {registration.register(raceEmail,"Race",account.passwordHash());return null;},
                    () -> {registration.register(raceEmail,"Race",account.passwordHash());return null;}));
                for(var task:tasks)task.get();
            }
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM users WHERE email_normalized=?",Integer.class,raceEmail));
            jdbc.update("DELETE FROM users WHERE email_normalized IN (?,?,?)",email,google.email(),raceEmail);
        }
    }
}
