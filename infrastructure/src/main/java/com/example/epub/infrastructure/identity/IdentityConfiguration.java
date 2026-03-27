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
    @Bean public com.example.epub.application.identity.GoogleAccounts googleAccounts(org.springframework.jdbc.core.JdbcTemplate jdbc,
            org.springframework.transaction.PlatformTransactionManager manager,com.example.epub.application.identity.AccountStore accounts) {
        return new JdbcGoogleAccounts(jdbc,new org.springframework.transaction.support.TransactionTemplate(manager),accounts);
    }
    @Bean public JdbcAccessChanges accessChanges(org.springframework.jdbc.core.JdbcTemplate jdbc,
            org.springframework.transaction.PlatformTransactionManager manager,com.example.epub.application.identity.AccountStore accounts) {
        return new JdbcAccessChanges(jdbc,new org.springframework.transaction.support.TransactionTemplate(manager),accounts);
    }
    @Bean public com.example.epub.application.identity.AccessManagement accessManagement(
            com.example.epub.application.identity.AccountStore accounts,JdbcAccessChanges changes) {
        return new com.example.epub.application.identity.AccessManagement(accounts,changes);
    }
    @Bean public com.example.epub.application.identity.AccountStore accountStore(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return new JdbcAccountStore(jdbc);
    }
    @Bean public UserAccounts userAccounts(JpaUserRepository repository) { return new JpaUserAccounts(repository); }
}
