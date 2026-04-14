package com.example.epub.api.auth;

import com.example.epub.api.ApiApplication;
import com.example.epub.core.service.identity.*;
import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="EPUB_IDENTITY_DB_TEST",matches="true")
class GoogleOidcIT {
    static String provider;
    @Configuration @Profile("oidc-test")
    static class ProviderConfig {
        @Bean @Primary ClientRegistrationRepository fakeProvider() {
            return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId("test-client").clientSecret("test-client-secret").redirectUri("{baseUrl}/api/v1/login/oauth2/code/google")
                .authorizationUri(provider+"/authorize").tokenUri(provider+"/token").jwkSetUri(provider+"/jwks")
                .scope("openid","profile","email").build());
        }
    }
    static Map<String,String> query(String value) {
        Map<String,String> map=new HashMap<>();
        for(String part:value.split("&")) {var pair=part.split("=",2);map.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),pair.length>1?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"");}
        return map;
    }
    static String begin(AuthSessionIT.Browser browser,Map<String,Map<String,String>> codes,String start,String subject,String email) throws Exception {
        var response=browser.call("GET",start,null,false);assertEquals(302,response.statusCode());
        var data=query(URI.create(response.headers().firstValue("Location").orElseThrow()).getRawQuery());
        String code=UUID.randomUUID().toString();data.put("subject",subject);data.put("email",email);data.put("mode","valid");codes.put(code,data);
        return "/api/v1/login/oauth2/code/google?code="+code+"&state="+URLEncoder.encode(data.get("state"),StandardCharsets.UTF_8);
    }
    @Test void oidcCodeExchangeAndTokenValidationUseRealHttpWithoutPersistingProviderTokens() throws Exception {
        var key=new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        var wrong=new RSAKeyGenerator(2048).keyID(key.getKeyID()).generate();
        var codes=new ConcurrentHashMap<String,Map<String,String>>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        provider="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/jwks",exchange -> {
            byte[] body=new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.createContext("/token",exchange -> {
            try {
                var input=query(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                var data=codes.remove(input.get("code"));
                if(data==null || "token-error".equals(data.get("mode"))) {
                    byte[] body="{\"error\":\"invalid_grant\"}".getBytes();exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(400,body.length);exchange.getResponseBody().write(body);return;
                }
                String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(input.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
                if(!challenge.equals(data.get("code_challenge")))throw new IllegalArgumentException("Invalid PKCE verifier");
                String mode=data.get("mode");Instant now=Instant.now();
                var claims=new JWTClaimsSet.Builder().issuer("issuer".equals(mode)?"https://wrong.example": "https://accounts.google.com")
                    .subject(data.get("subject")).audience("audience".equals(mode)?"wrong-client":"test-client")
                    .issueTime(Date.from(now.minusSeconds("expiry".equals(mode)?7200:10)))
                    .expirationTime(Date.from(now.plusSeconds("expiry".equals(mode)?-3600:300)))
                    .claim("email",data.get("email")).claim("email_verified",!"unverified".equals(mode)).claim("name","Google reader")
                    .claim("nonce","missing-nonce".equals(mode)?null:"nonce".equals(mode)?"incorrect-nonce":data.get("nonce")).build();
                var token=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),claims);
                token.sign(new RSASSASigner("signature".equals(mode)?wrong:key));
                byte[] body=JsonMapper.builder().build().writeValueAsBytes(Map.of("access_token","sentinel-google-access-token","token_type","Bearer","expires_in",300,"id_token",token.serialize()));
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            } catch(Exception error) {exchange.sendResponseHeaders(500,-1);} finally {exchange.close();}
        });
        server.start();
        try(var context=new SpringApplicationBuilder(ApiApplication.class,ProviderConfig.class).run("--spring.profiles.active=local,oidc-test","--server.port=0",
                "--app.google.enabled=true","--app.google.client-id=test-client","--app.google.client-secret=test-client-secret",
                "--app.google.redirect-uri=http://localhost:5173/api/v1/login/oauth2/code/google",
                "--app.local-seed.enabled=false","--logging.level.root=WARN","--debug=false")) {
            int port=((WebServerApplicationContext)context).getWebServer().getPort();var jdbc=context.getBean(JdbcTemplate.class);
            String subject=UUID.randomUUID().toString();String email="oidc-"+subject+"@gmail.com";
            try(var browser=new AuthSessionIT.Browser()) {
                browser.port=port;browser.csrf();String before=browser.cookie();
                var start=browser.call("GET","/api/v1/oauth2/authorization/google?returnTo=/account",null,false);
                assertEquals(302,start.statusCode());var params=query(URI.create(start.headers().firstValue("Location").orElseThrow()).getRawQuery());
                assertEquals("S256",params.get("code_challenge_method"));assertNotNull(params.get("nonce"));
                String code=UUID.randomUUID().toString();params.put("subject",subject);params.put("email",email);params.put("mode","valid");codes.put(code,params);
                String callback="/api/v1/login/oauth2/code/google?code="+code+"&state="+URLEncoder.encode(params.get("state"),StandardCharsets.UTF_8);
                var response=browser.call("GET",callback,null,false);assertEquals(302,response.statusCode());
                assertEquals("http://localhost:5173/#/account",response.headers().firstValue("Location").orElseThrow());assertNotEquals(before,browser.cookie());
                var me=browser.call("GET","/api/v1/auth/me",null,false);assertEquals(200,me.statusCode(),response.body());assertTrue(me.body().contains("USER"));
                assertTrue(me.body().contains("\"emailVerified\":true"));
                var profile=browser.call("GET","/api/v1/account",null,false);assertTrue(profile.body().contains("\"hasPassword\":false"));
                browser.csrf();assertEquals(409,browser.call("POST","/api/v1/account/change-password","{\"currentPassword\":\"arbitrary password\",\"newPassword\":\"Updated password 123\"}",true).statusCode());
                try(var resumed=new SpringApplicationBuilder(ApiApplication.class).run("--spring.profiles.active=local","--server.port=0",
                        "--app.google.enabled=false","--app.local-seed.enabled=false","--logging.level.root=WARN","--debug=false")) {
                    browser.port=((WebServerApplicationContext)resumed).getWebServer().getPort();
                    assertEquals(200,browser.call("GET","/api/v1/auth/me",null,false).statusCode(),"A new API instance resumes the Google-created JDBC session without provider tokens");
                    browser.port=port;
                }
                assertNull(jdbc.queryForObject("SELECT password_hash FROM users WHERE email_normalized=?",String.class,email));
                for(byte[] value:jdbc.queryForList("SELECT attribute_bytes FROM spring_session_attributes",byte[].class)) {
                    String serialized=new String(value,StandardCharsets.ISO_8859_1);
                    assertFalse(serialized.contains("sentinel-google-access-token"));assertFalse(serialized.contains("OidcIdToken"));
                    assertFalse(serialized.contains("OAuth2AuthenticationToken"));
                }
                assertTrue(browser.call("GET",callback,null,false).headers().firstValue("Location").orElseThrow().contains("GOOGLE_LOGIN_FAILED"));
                browser.csrf();assertEquals(204,browser.call("POST","/api/v1/auth/logout",null,true).statusCode());
                browser.csrf();assertEquals(401,browser.call("POST","/api/v1/auth/login","{\"email\":\""+email+"\",\"password\":\"arbitrary password\"}",true).statusCode());
            }
            for(String mode:List.of("signature","issuer","audience","expiry","nonce","missing-nonce","unverified","token-error","state","missing-state","cancel")) {
                try(var browser=new AuthSessionIT.Browser()) {
                    browser.port=port;
                    var start=browser.call("GET","/api/v1/oauth2/authorization/google?returnTo=//evil.example",null,false);
                    var params=query(URI.create(start.headers().firstValue("Location").orElseThrow()).getRawQuery());
                    String code=UUID.randomUUID().toString();params.put("subject",UUID.randomUUID().toString());params.put("email",UUID.randomUUID()+"@gmail.com");params.put("mode",mode);codes.put(code,params);
                    String state="state".equals(mode)?"incorrect":params.get("state");
                    String path="/api/v1/login/oauth2/code/google?"+("cancel".equals(mode)?"error=access_denied":"code="+code)
                            +("missing-state".equals(mode)?"":"&state="+URLEncoder.encode(state,StandardCharsets.UTF_8));
                    var response=browser.call("GET",path,null,false);
                    assertEquals(302,response.statusCode(),mode+response.body());
                    assertEquals("http://localhost:5173/#/login?error=GOOGLE_LOGIN_FAILED",response.headers().firstValue("Location").orElseThrow(),mode);
                    assertEquals(401,browser.call("GET","/api/v1/auth/me",null,false).statusCode(),mode);
                }
            }
            UUID local=UUID.randomUUID(),other=UUID.randomUUID();String password="Linking password 123",localMail=local+"@example.test",otherMail=other+"@example.test";
            var encoder=context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class);
            for(UUID id:List.of(local,other))jdbc.update("INSERT INTO users(id,email_normalized,password_hash,display_name) VALUES (?,?,?,'Local reader')",id,id.equals(local)?localMail:otherMail,encoder.encode(password));
            String linkSubject=UUID.randomUUID().toString();
            try(var browser=new AuthSessionIT.Browser();var second=new AuthSessionIT.Browser()) {
                browser.port=port;second.port=port;
                String collision=begin(browser,codes,"/api/v1/oauth2/authorization/google",linkSubject,localMail);
                assertTrue(browser.call("GET",collision,null,false).headers().firstValue("Location").orElseThrow().contains("GOOGLE_LOGIN_FAILED"));
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_identities WHERE user_id=?",Integer.class,local));
                browser.csrf();assertEquals(200,browser.call("POST","/api/v1/auth/login","{\"email\":\""+localMail+"\",\"password\":\""+password+"\"}",true).statusCode());browser.csrf();
                String reauth="{\"currentPassword\":\""+password+"\"}";
                assertEquals(403,browser.call("POST","/api/v1/account/google/link",reauth,false).statusCode());
                assertEquals(400,browser.call("POST","/api/v1/account/google/link",reauth.replace(password,"Incorrect password"),true).statusCode());
                var link=browser.call("POST","/api/v1/account/google/link",reauth,true);assertEquals(200,link.statusCode(),link.body());
                String start=JsonMapper.builder().build().readTree(link.body()).get("authorizationUrl").asText();
                String callback=begin(browser,codes,start,linkSubject,"different@gmail.com");
                assertEquals("http://localhost:5173/#/account",browser.call("GET",callback,null,false).headers().firstValue("Location").orElseThrow());
                assertTrue(browser.call("GET","/api/v1/auth/me",null,false).body().contains(local.toString()));
                var linked=browser.call("GET","/api/v1/account",null,false);assertTrue(linked.body().contains("GOOGLE"));assertTrue(linked.body().contains(localMail));
                assertTrue(linked.body().contains("\"emailVerified\":false"),"Linking does not verify or change the local account email");
                browser.csrf();assertEquals(204,browser.call("POST","/api/v1/auth/logout",null,true).statusCode());
                var google=begin(browser,codes,"/api/v1/oauth2/authorization/google",linkSubject,"different@gmail.com");
                browser.call("GET",google,null,false);assertTrue(browser.call("GET","/api/v1/auth/me",null,false).body().contains(local.toString()));
                second.csrf();assertEquals(200,second.call("POST","/api/v1/auth/login","{\"email\":\""+otherMail+"\",\"password\":\""+password+"\"}",true).statusCode());second.csrf();
                var secondLink=second.call("POST","/api/v1/account/google/link",reauth,true);assertEquals(200,secondLink.statusCode());
                String secondStart=JsonMapper.builder().build().readTree(secondLink.body()).get("authorizationUrl").asText();
                String steal=begin(second,codes,secondStart,linkSubject,localMail);
                assertTrue(second.call("GET",steal,null,false).headers().firstValue("Location").orElseThrow().contains("GOOGLE_LOGIN_FAILED"));
                assertTrue(second.call("GET","/api/v1/auth/me",null,false).body().contains(other.toString()));
                second.csrf();assertEquals(200,second.call("POST","/api/v1/account/google/link",reauth,true).statusCode());
                String switched=begin(second,codes,secondStart,UUID.randomUUID().toString(),"switch@gmail.com");
                second.csrf();assertEquals(200,second.call("POST","/api/v1/auth/login","{\"email\":\""+localMail+"\",\"password\":\""+password+"\"}",true).statusCode());
                assertTrue(second.call("GET",switched,null,false).headers().firstValue("Location").orElseThrow().contains("GOOGLE_LOGIN_FAILED"));
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_identities WHERE user_id=?",Integer.class,other));
            }
            jdbc.update("DELETE FROM users WHERE id IN (?,?)",local,other);
            jdbc.update("DELETE FROM users WHERE email_normalized=?",email);
        } finally {server.stop(0);}
    }
}
