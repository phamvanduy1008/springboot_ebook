package com.example.epub.core.config;

import com.example.epub.core.integration.storage.*;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after=RuntimeConfiguration.class)
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
public class StorageConfiguration {
    @Bean public ObjectStorage objectStorage(RuntimeProperties properties) throws IOException {
        return new LocalObjectStorage(Path.of(properties.storageRoot()));
    }
}
