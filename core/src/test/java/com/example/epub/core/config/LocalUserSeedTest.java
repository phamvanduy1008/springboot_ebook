package com.example.epub.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class LocalUserSeedTest {
    @Test void neverRegistersSeedInProdEvenIfEnabled() {
        new ApplicationContextRunner().withUserConfiguration(LocalUserSeed.class)
                .withPropertyValues("spring.profiles.active=prod","app.local-seed.enabled=true")
                .run(context -> {assertThat(context).hasNotFailed();assertThat(context).doesNotHaveBean("seedLocalUser");});
    }
    @Test void localSeedIsOffUnlessExplicitlyEnabled() {
        new ApplicationContextRunner().withUserConfiguration(LocalUserSeed.class)
                .withPropertyValues("spring.profiles.active=local")
                .run(context -> {assertThat(context).hasNotFailed();assertThat(context).doesNotHaveBean("seedLocalUser");});
    }
}
