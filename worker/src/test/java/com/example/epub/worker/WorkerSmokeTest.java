package com.example.epub.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

class WorkerSmokeTest {
    @Test
    void startsAndClosesWithoutHttpDatabaseOrNativeRuntime() {
        var application = new SpringApplication(WorkerApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        ConfigurableApplicationContext context = application.run("--spring.profiles.active=test", "--debug=false", "--logging.level.root=WARN");
        try {
            assertTrue(context.isActive());
            assertFalse(context.getClass().getName().contains("WebServer"));
            assertEquals(0, context.getBeanNamesForType(javax.sql.DataSource.class).length);
            assertThrows(ClassNotFoundException.class, () -> Class.forName("ai.onnxruntime.OrtEnvironment"));
        } finally {
            context.close();
        }
        assertFalse(context.isActive());
    }
}
