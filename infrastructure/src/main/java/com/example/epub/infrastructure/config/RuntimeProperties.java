package com.example.epub.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record RuntimeProperties(String databaseUrl, String databaseUsername, String databasePassword,
        String smtpHost, int smtpPort, String storageRoot, String publicOrigin) {
    // Configuration objects must never reveal credentials through generated record output.
    @Override public String toString() { return "RuntimeProperties[credentials and connection details redacted]"; }
}
