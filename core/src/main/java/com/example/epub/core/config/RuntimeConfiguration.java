package com.example.epub.core.config;

import java.net.URI;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@AutoConfiguration
@EnableConfigurationProperties(RuntimeProperties.class)
public class RuntimeConfiguration {
    @Bean
    public RuntimeValidation runtimeValidation(RuntimeProperties config, Environment environment) {
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        if (prod && environment.acceptsProfiles(Profiles.of("local", "test"))) {
            throw new IllegalStateException("prod cannot be combined with local or test");
        }
        if (prod) {
            require(config.databaseUrl(), "DB_URL");
            require(config.databaseUsername(), "DB_USERNAME");
            require(config.databasePassword(), "DB_PASSWORD");
            require(config.smtpHost(), "SMTP_HOST");
            require(config.storageRoot(), "STORAGE_ROOT");
            require(config.publicOrigin(), "APP_PUBLIC_ORIGIN");
        }
        if (config.databaseUrl() != null && !config.databaseUrl().isBlank()
                && (!config.databaseUrl().startsWith("jdbc:postgresql:")
                || config.databaseUrl().matches("(?is).*([?&;])(password|user)=.*"))) {
            throw new IllegalStateException("DB_URL must be PostgreSQL JDBC without embedded credentials");
        }
        if (config.smtpPort() < 1 || config.smtpPort() > 65535) throw new IllegalStateException("SMTP_PORT must be 1..65535");
        try {
            URI origin = URI.create(config.publicOrigin());
            if (origin.getHost() == null || origin.getUserInfo() != null || origin.getQuery() != null
                    || origin.getFragment() != null || (origin.getPath() != null && !origin.getPath().isEmpty() && !origin.getPath().equals("/"))
                    || !("https".equals(origin.getScheme()) || (!prod && "http".equals(origin.getScheme())))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) { throw new IllegalStateException("APP_PUBLIC_ORIGIN must be an origin; prod requires HTTPS"); }
        return new RuntimeValidation();
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required in prod");
    }
    public static final class RuntimeValidation { }
}
