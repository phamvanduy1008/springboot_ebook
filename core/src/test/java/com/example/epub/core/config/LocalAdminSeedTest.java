package com.example.epub.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class LocalAdminSeedTest {
    @Test void neverSeedsOutsideLocalProfile() {
        new ApplicationContextRunner().withUserConfiguration(LocalAdminSeed.class)
            .withPropertyValues("spring.profiles.active=prod","app.local-admin-seed.enabled=true")
            .run(context->{assertThat(context).hasNotFailed();assertThat(context).doesNotHaveBean("seedLocalAdmin");});
    }
    @Test void localSeedRequiresExplicitOptIn() {
        new ApplicationContextRunner().withUserConfiguration(LocalAdminSeed.class)
            .withPropertyValues("spring.profiles.active=local")
            .run(context->{assertThat(context).hasNotFailed();assertThat(context).doesNotHaveBean("seedLocalAdmin");});
    }
}
