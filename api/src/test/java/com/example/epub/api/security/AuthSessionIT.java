package com.example.epub.api.security;

import com.example.epub.api.ApiApplication;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="EPUB_IDENTITY_DB_TEST",matches="true")
class AuthSessionIT {
    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(ApiApplication.class).run("--spring.profiles.active=local","--server.port=0",
                "--app.local-seed.enabled=false","--logging.level.root=WARN","--debug=false");
    }
    static final class Browser implements AutoCloseable {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        int port;
        String token;
        String header;
        HttpResponse<String> call(String method,String path,String body,boolean csrf) throws Exception {
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
            if(csrf)builder.header(header,token);
            if(body!=null)builder.header("Content-Type","application/json");
            return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        void csrf() throws Exception {
            var response=call("GET","/api/v1/auth/csrf",null,false);assertEquals(200,response.statusCode(),response.body());
            var json=JsonMapper.builder().build().readTree(response.body());token=json.get("token").asText();header=json.get("headerName").asText();
        }
        String cookie() { return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("SESSION")).findFirst().orElseThrow().getValue(); }
        @Override public void close() {client.close();}
    }
    @Test void csrfFixationLogoutThrottleAndPersistentSessionsWorkOverRealHttpAndPostgres() throws Exception {
        var context=start();
        UUID id=UUID.randomUUID();String email="auth-"+id+"@example.test";
        String login="{\"email\":\""+email+"\",\"password\":\"Correct password 123\"}";
        try(var browser=new Browser();var attacker=new Browser()) {
            browser.port=((WebServerApplicationContext)context).getWebServer().getPort();attacker.port=browser.port;
            var jdbc=context.getBean(JdbcTemplate.class);
            jdbc.update("INSERT INTO users(id,email_normalized,password_hash,display_name) VALUES (?,?,?,?)",id,email,
                    new BCryptPasswordEncoder(12).encode("Correct password 123"),"Reader");
            assertEquals(200,browser.call("GET","/api/v1/health",null,false).statusCode());
            var guest=browser.call("GET","/api/v1/auth/me",null,false);
            assertEquals(401,guest.statusCode());assertTrue(guest.headers().firstValue("Content-Type").orElseThrow().contains("application/problem+json"));
            assertEquals(403,browser.call("POST","/api/v1/auth/login",login,false).statusCode());
            browser.csrf();String before=browser.cookie();
            attacker.cookies.getCookieStore().add(URI.create("http://127.0.0.1"),new HttpCookie("SESSION",before));
            String valid=browser.token;browser.token="invalid";
            assertEquals(403,browser.call("POST","/api/v1/auth/login",login,true).statusCode());browser.token=valid;
            var authenticated=browser.call("POST","/api/v1/auth/login",login,true);
            assertEquals(200,authenticated.statusCode(),authenticated.body());assertNotEquals(before,browser.cookie());
            assertFalse(authenticated.body().contains("password"));assertFalse(authenticated.body().contains("SESSION"));
            var cookie=authenticated.headers().allValues("Set-Cookie").toString();
            assertTrue(cookie.contains("HttpOnly"),cookie);assertTrue(cookie.contains("SameSite=Lax"),cookie);
            assertEquals(401,attacker.call("GET","/api/v1/auth/me",null,false).statusCode());
            assertEquals(200,browser.call("GET","/api/v1/auth/me",null,false).statusCode());
            context.close();context=start();
            browser.port=((WebServerApplicationContext)context).getWebServer().getPort();
            assertEquals(200,browser.call("GET","/api/v1/auth/me",null,false).statusCode(),"JDBC session survives API restart");
            assertEquals(403,browser.call("POST","/api/v1/auth/logout",null,false).statusCode());
            browser.csrf();assertEquals(204,browser.call("POST","/api/v1/auth/logout",null,true).statusCode());
            assertEquals(401,browser.call("GET","/api/v1/auth/me",null,false).statusCode());
            browser.csrf();
            for(int attempt=0;attempt<9;attempt++)assertEquals(401,browser.call("POST","/api/v1/auth/login",login.replace("Correct password 123","Wrong password 456"),true).statusCode());
            assertEquals(429,browser.call("POST","/api/v1/auth/login",login,true).statusCode());
            context.getBean(JdbcTemplate.class).update("DELETE FROM users WHERE id=?",id);
        } finally {context.close();}
    }
}
