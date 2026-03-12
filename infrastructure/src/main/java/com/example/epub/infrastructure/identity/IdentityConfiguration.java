package com.example.epub.infrastructure.identity;

import com.example.epub.application.identity.UserAccounts;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@ConditionalOnProperty(name="app.persistence.enabled", havingValue="true")
@EntityScan(basePackageClasses=UserEntity.class)
@EnableJpaRepositories(basePackageClasses=JpaUserRepository.class)
public class IdentityConfiguration {
    @Bean public UserAccounts userAccounts(JpaUserRepository repository) { return new JpaUserAccounts(repository); }
}
