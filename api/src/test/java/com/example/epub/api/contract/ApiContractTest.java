package com.example.epub.api.contract;

import com.example.epub.api.ApiApplication;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import com.example.epub.core.config.RuntimeProperties;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class ApiContractTest {
    private static final AtomicBoolean DOWN=new AtomicBoolean();
    @Profile("contract-test") @RestController
    static class ProbeController {
        @GetMapping("/__contract/page") PageResult<String> page(@Valid @ModelAttribute PageQuery query) {
            query.orders(Set.of("id","title"));return new PageResult<>(List.of(),query.page(),query.size(),0);
        }
        @GetMapping("/__contract/fail") String fail() {throw new IllegalStateException("sentinel-secret SQL stacktrace");}
        @GetMapping("/__contract/status") String status(@RequestParam int code) {throw new ResponseStatusException(HttpStatus.valueOf(code),"sentinel-secret");}
        @Bean HealthIndicator contractHealth() {return () -> DOWN.get()?Health.down().build():Health.up().build();}
    }

    @Test void openApiIsValidAndRealHttpResponsesMatchDocumentedShapes() throws Exception {
        var options=new ParseOptions();options.setResolve(false);
        var parsed=new OpenAPIV3Parser().readLocation(getClass().getResource("/openapi.yaml").toURI().toString(),null,options);
        assertNotNull(parsed.getOpenAPI(),String.valueOf(parsed.getMessages()));
        assertTrue(parsed.getMessages()==null || parsed.getMessages().isEmpty(),String.valueOf(parsed.getMessages()));
        var spec=parsed.getOpenAPI();assertTrue(spec.getPaths().keySet().containsAll(Set.of("/api/v1/health","/api/v1/auth/login","/api/v1/auth/me","/api/v1/auth/csrf","/api/v1/auth/logout")));
        try (var context=new SpringApplicationBuilder(ApiApplication.class,ProbeController.class)
                .run("--server.port=0","--spring.profiles.active=test,contract-test","--debug=false","--logging.level.root=WARN");
             var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            int port=((WebServerApplicationContext)context).getWebServer().getPort();
            var health=request(client,port,"/api/v1/health","GET");assertEquals(200,health.statusCode());
            var json=JsonMapper.builder().build().readTree(health.body());assertEquals("UP",json.get("status").asText());
            assertEquals(spec.getComponents().getSchemas().get("Health").getProperties().size(),json.size());
            DOWN.set(true);assertEquals(503,request(client,port,"/api/v1/health","GET").statusCode());DOWN.set(false);
            for (var input:List.of(new String[]{"/api/v1/missing","404"},new String[]{"/__contract/page?size=101","400"},
                    new String[]{"/__contract/page?page=-1","400"},new String[]{"/__contract/page?page=text","400"},
                    new String[]{"/__contract/page?sort=passwordHash,asc","400"},new String[]{"/__contract/fail","500"},
                    new String[]{"/__contract/status?code=401","401"},new String[]{"/__contract/status?code=403","403"})) {
                var response=request(client,port,input[0],"GET");assertEquals(Integer.parseInt(input[1]),response.statusCode(),response.body());
                assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
                var problem=JsonMapper.builder().build().readTree(response.body());
                io.swagger.v3.oas.models.media.Schema<?> schema=spec.getComponents().getSchemas().get("Problem");
                for (String property:schema.getRequired())assertTrue(problem.has(property),property);
                assertEquals(schema.getProperties().size(),problem.size());
                assertEquals(response.statusCode(),problem.get("status").asInt());
                assertEquals("contract-123",problem.get("requestId").asText());
                assertEquals("contract-123",response.headers().firstValue("X-Request-ID").orElseThrow());
                assertFalse(response.body().contains("sentinel-secret"));assertFalse(problem.has("trace"));
            }
            assertEquals(405,request(client,port,"/api/v1/health","POST").statusCode());
            var page=request(client,port,"/__contract/page?page=0&size=100&sort=title,desc","GET");assertEquals(200,page.statusCode(),page.body());
            var envelope=JsonMapper.builder().build().readTree(page.body());
            assertEquals(spec.getComponents().getSchemas().get("Page").getProperties().size(),envelope.size());
        } finally {DOWN.set(false);}
    }

    @Test void paginationBoundsAndStableSortPolicyAreEnforced() {
        var query=new PageQuery(null,null,null);assertEquals(0,query.page());assertEquals(20,query.size());
        try (var factory=Validation.buildDefaultValidatorFactory()) {
            assertFalse(factory.getValidator().validate(new PageQuery(-1,101,"id,asc")).isEmpty());
            assertTrue(factory.getValidator().validate(new PageQuery(0,100,"id,asc")).isEmpty());
        }
        var orders=new PageQuery(2,10,"title,desc").orders(Set.of("id","title"));
        assertEquals(List.of(new PageQuery.SortOrder("title",PageQuery.Direction.DESC),new PageQuery.SortOrder("id",PageQuery.Direction.ASC)),orders);
        assertEquals(20,new PageQuery(2,10,null).offset());
        assertThrows(IllegalArgumentException.class,() -> new PageQuery(0,20,"title,desc;DROP TABLE users").orders(Set.of("title")));
        assertThrows(IllegalArgumentException.class,() -> new PageQuery(0,20,"passwordHash,asc").orders(Set.of("title")));
    }
    @Test void dotenvProfileIsAppliedButTestProfileDoesNotUseItsDatabaseSecret(@TempDir Path directory) throws Exception {
        Path dotenv=directory.resolve(".env");
        Files.writeString(dotenv,"SPRING_PROFILES_ACTIVE=test\nDB_PASSWORD=sentinel-config-password\nAPP_LOCAL_SEED_ENABLED=true\n");
        try (var context=new SpringApplicationBuilder(ApiApplication.class).web(org.springframework.boot.WebApplicationType.NONE)
                .run("--spring.config.import="+dotenv.toUri()+"[.properties],classpath:epub-runtime.properties","--debug=false","--logging.level.root=WARN")) {
            assertTrue(List.of(context.getEnvironment().getActiveProfiles()).contains("test"));
            assertTrue(context.getBean(RuntimeProperties.class).databasePassword().isEmpty());
            assertEquals("true",context.getEnvironment().getProperty("app.local-seed.enabled"));
            assertFalse(context.containsBean("seedLocalUser"));
        }
    }
    private static HttpResponse<String> request(HttpClient client,int port,String path,String method) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("X-Request-ID","contract-123")
                .timeout(Duration.ofSeconds(10)).method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    }
}
