package com.example.epub.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class RuntimeConfigurationTest {
    private ApplicationContextRunner runner(String profile) {
        return new ApplicationContextRunner().withUserConfiguration(RuntimeConfiguration.class)
                .withPropertyValues("spring.profiles.active=" + profile, "app.smtp-port=1025", "app.public-origin=http://localhost:5173");
    }
    @Test void bindsLocalConfigWithoutServicesAndRedactsRecord() {
        runner("local").withPropertyValues("app.database-url=jdbc:postgresql://127.0.0.1/epub", "app.database-password=sentinel-secret")
                .run(ctx -> { assertThat(ctx).hasNotFailed(); assertThat(ctx.getBean(RuntimeProperties.class).databaseUrl()).endsWith("/epub");
                    assertThat(ctx.getBean(RuntimeProperties.class).toString()).doesNotContain("sentinel-secret"); });
    }
    @Test void prodMissingSecretsFailsWithVariableNameOnly() {
        runner("prod").run(ctx -> assertThat(ctx.getStartupFailure()).hasRootCauseMessage("DB_URL is required in prod"));
    }
    @Test void prodRejectsLocalProfileAndUnsafeOrigin() {
        runner("prod,local").run(ctx -> assertThat(ctx.getStartupFailure()).hasRootCauseMessage("prod cannot be combined with local or test"));
        runner("prod").withPropertyValues("app.database-url=jdbc:postgresql://localhost/epub", "app.database-username=test",
                "app.database-password=sentinel-secret", "app.smtp-host=smtp.example.com", "app.storage-root=/data")
                .run(ctx -> assertThat(ctx.getStartupFailure()).hasRootCauseMessage("APP_PUBLIC_ORIGIN must be an origin; prod requires HTTPS"));
    }
    @Test void rejectsCredentialsInJdbcUrlWithoutEchoingThem() {
        runner("local").withPropertyValues("app.database-url=jdbc:postgresql://localhost/epub?password=sentinel-secret")
                .run(ctx -> assertThat(ctx.getStartupFailure()).hasRootCauseMessage("DB_URL must be PostgreSQL JDBC without embedded credentials"));
    }
}
