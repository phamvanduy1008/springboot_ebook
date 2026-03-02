package com.example.epub.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WorkerApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(WorkerApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        // Keep the headless process alive without fake jobs or a polling loop.
        application.setKeepAlive(true);
        application.run(args);
    }
}
