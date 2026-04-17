package com.example.epub.api.security;

import com.example.epub.api.ApiApplication;
import com.example.epub.core.repository.identity.AccountRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="EPUB_IDENTITY_DB_TEST",matches="true")
class AccountSecurityIT {
    @Test void profileAllowlistPasswordRotationAndOwnedSessionRevocationWorkOverHttp() throws Exception {
        try(var context=new SpringApplicationBuilder(ApiApplication.class).run("--spring.profiles.active=local","--server.port=0",
                "--app.local-seed.enabled=false","--logging.level.root=WARN","--debug=false");
            var a=new AuthSessionIT.Browser();var other=new AuthSessionIT.Browser();var b=new AuthSessionIT.Browser()) {
            int port=((WebServerApplicationContext)context).getWebServer().getPort();a.port=port;other.port=port;b.port=port;
            a.csrf();other.csrf();b.csrf();
            var jdbc=context.getBean(JdbcTemplate.class);var accounts=context.getBean(AccountRepository.class);
            UUID id=UUID.randomUUID(),bid=UUID.randomUUID();String email="account-"+id+"@example.test",bmail="account-"+bid+"@example.test";
            String old="Original password 123",replacement="Updated password 456";
            for(UUID user:List.of(id,bid))jdbc.update("INSERT INTO users(id,email_normalized,password_hash,display_name) VALUES (?,?,?,'Reader')",user,user.equals(id)?email:bmail,new BCryptPasswordEncoder(12).encode(old));
            String login="{\"email\":\""+email+"\",\"password\":\""+old+"\"}";
            assertEquals(200,a.call("POST","/api/v1/auth/login",login,true).statusCode());
            assertEquals(200,other.call("POST","/api/v1/auth/login",login,true).statusCode());
            assertEquals(200,b.call("POST","/api/v1/auth/login",login.replace(email,bmail),true).statusCode());
            a.csrf();b.csrf();String before=a.cookie();
            assertEquals(400,a.call("PATCH","/api/v1/account","{\"role\":\"ADMIN\"}",true).statusCode());
            assertEquals(400,a.call("PATCH","/api/v1/account","{\"email\":\"attacker@example.test\"}",true).statusCode());
            assertEquals("USER",accounts.byId(id).orElseThrow().role());
            var updated=a.call("PATCH","/api/v1/account","{\"displayName\":\"Updated Reader\",\"bio\":\"My reading notes\"}",true);
            assertEquals(200,updated.statusCode(),updated.body());assertTrue(updated.body().contains("Updated Reader"));
            assertFalse(updated.body().contains("passwordHash"));assertFalse(updated.body().contains("subject"));
            var mapper=JsonMapper.builder().build();
            var own=mapper.readTree(a.call("GET","/api/v1/account/sessions",null,false).body());assertEquals(2,own.size());
            String otherId="";for(var session:own)if(!session.get("current").asBoolean())otherId=session.get("id").asText();
            assertFalse(own.toString().contains(a.cookie()));assertFalse(own.toString().contains(other.cookie()));
            String foreign=mapper.readTree(b.call("GET","/api/v1/account/sessions",null,false).body()).get(0).get("id").asText();
            assertEquals(404,a.call("DELETE","/api/v1/account/sessions/"+foreign,null,true).statusCode());
            assertEquals(204,a.call("DELETE","/api/v1/account/sessions/"+otherId,null,true).statusCode());
            assertEquals(401,other.call("GET","/api/v1/auth/me",null,false).statusCode());
            other.csrf();assertEquals(200,other.call("POST","/api/v1/auth/login",login,true).statusCode());
            String change="{\"currentPassword\":\""+old+"\",\"newPassword\":\""+replacement+"\"}";
            var wrong=a.call("POST","/api/v1/account/change-password",change.replace(old,"Incorrect password"),true);
            assertEquals(400,wrong.statusCode());assertTrue(wrong.body().contains("currentPassword"));
            assertTrue(new BCryptPasswordEncoder().matches(old,accounts.byId(id).orElseThrow().passwordHash()));
            assertEquals(200,a.call("POST","/api/v1/account/change-password",change,true).statusCode());assertNotEquals(before,a.cookie());
            assertEquals(200,a.call("GET","/api/v1/auth/me",null,false).statusCode());
            assertEquals(401,other.call("GET","/api/v1/auth/me",null,false).statusCode());
            assertEquals(200,b.call("GET","/api/v1/auth/me",null,false).statusCode());
            other.csrf();assertEquals(401,other.call("POST","/api/v1/auth/login",login,true).statusCode());
            assertEquals(200,other.call("POST","/api/v1/auth/login",login.replace(old,replacement),true).statusCode());
            a.csrf();var active=mapper.readTree(a.call("GET","/api/v1/account/sessions",null,false).body());
            for(var session:active)if(session.get("current").asBoolean())assertEquals(204,a.call("DELETE","/api/v1/account/sessions/"+session.get("id").asText(),null,true).statusCode());
            assertEquals(401,a.call("GET","/api/v1/auth/me",null,false).statusCode());
            jdbc.update("DELETE FROM users WHERE id IN (?,?)",id,bid);
        }
    }
}
