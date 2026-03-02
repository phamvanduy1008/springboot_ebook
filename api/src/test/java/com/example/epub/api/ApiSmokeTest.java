package com.example.epub.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class ApiSmokeTest {
    @Test
    void servesHealthOverRealHttpAndExposesNoOtherActuatorEndpoints() throws Exception {
        try (var context = SpringApplication.run(ApiApplication.class, "--server.port=0", "--debug=false", "--logging.level.root=WARN")) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                for (String endpoint : new String[]{"health", "health/liveness", "health/readiness"}) {
                    var response = get(client, port, endpoint);
                    assertEquals(200, response.statusCode());
                    var health = JsonMapper.builder().build().readTree(response.body());
                    assertEquals("UP", health.get("status").asText());
                    assertFalse(health.has("components"));
                }
                assertEquals(404, get(client, port, "env").statusCode());
                assertEquals(404, get(client, port, "beans").statusCode());
            }
        }
    }

    private static HttpResponse<String> get(HttpClient client, int port, String endpoint) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/" + endpoint))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
