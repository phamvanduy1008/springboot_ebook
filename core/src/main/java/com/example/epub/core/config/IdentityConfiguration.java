package com.example.epub.core.config;
import com.example.epub.core.entity.identity.UserEntity;
import com.example.epub.core.repository.identity.*;
import com.example.epub.core.service.identity.*;
import com.example.epub.core.integration.mail.MailGateway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@AutoConfiguration
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
@EntityScan(basePackageClasses=UserEntity.class)
@EnableJpaRepositories(basePackageClasses=JpaUserRepository.class)
public class IdentityConfiguration {
    @Bean public AccountRepository accountRepository(JdbcTemplate jdbc) { return new JdbcAccountRepository(jdbc); }
    @Bean public AccountService accountService(AccountRepository accounts) { return new AccountService(accounts); }
    @Bean public RegistrationRepository registrationRepository(JdbcTemplate jdbc) { return new RegistrationRepository(jdbc); }
    @Bean public EmailTokenRepository emailTokenRepository(JdbcTemplate jdbc) { return new EmailTokenRepository(jdbc); }
    @Bean public AccountMutationRepository accountMutationRepository(JdbcTemplate jdbc) { return new AccountMutationRepository(jdbc); }
    @Bean public GoogleIdentityRepository googleIdentityRepository(JdbcTemplate jdbc) { return new GoogleIdentityRepository(jdbc); }
    @Bean public AccessRepository accessRepository(JdbcTemplate jdbc) { return new AccessRepository(jdbc); }
    @Bean public AuditRepository auditRepository(JdbcTemplate jdbc) { return new AuditRepository(jdbc); }
    @Bean public MailGateway mailGateway(JavaMailSender mail,Environment env) {
        String frontend=env.getRequiredProperty("app.public-origin")+(env.getProperty("app.frontend.hash-routing",Boolean.class,true)?"/#":"");
        return new MailGateway(mail,frontend,env.getProperty("app.mail.from","noreply@example.test"));
    }
    @Bean public EmailTokenService emailTokenService(EmailTokenRepository tokens,MailGateway mail) { return new EmailTokenService(tokens,mail); }
    @Bean public AccessChangeService accessChanges(AccessRepository access,AuditRepository audits,AccountMutationRepository changes,PlatformTransactionManager manager,AccountRepository accounts) {
        return new AccessChangeService(access,audits,changes,new TransactionTemplate(manager),accounts);
    }
    @Bean public AccessManagement accessManagement(AccountRepository accounts,AccessChanges changes) { return new AccessManagement(accounts,changes); }
    @Bean public Registration registration(RegistrationRepository registration,PlatformTransactionManager manager,AccountRepository accounts,EmailTokenService tokens) {
        return new RegistrationService(registration,new TransactionTemplate(manager),accounts,tokens);
    }
    @Bean public PasswordRecovery passwordRecovery(AccountMutationRepository changes,PlatformTransactionManager manager,AccountRepository accounts,EmailTokenService tokens,AuditEvents audits) {
        return new PasswordRecoveryService(changes,new TransactionTemplate(manager),accounts,tokens,audits);
    }
    @Bean public GoogleAccounts googleAccounts(GoogleIdentityRepository identities,AccountMutationRepository changes,PlatformTransactionManager manager,AccountRepository accounts) {
        return new GoogleAccountService(identities,changes,new TransactionTemplate(manager),accounts);
    }
    @Bean public AccountSecurity accountSecurity(AccountMutationRepository changes,PlatformTransactionManager manager,AccountRepository accounts,AuditEvents audits) {
        return new AccountSecurityService(changes,new TransactionTemplate(manager),accounts,audits);
    }
    @Bean public UserAccounts userAccounts(JpaUserRepository repository) { return new UserAccountService(repository); }
}
