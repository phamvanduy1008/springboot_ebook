package com.example.epub.api.security;

import com.example.epub.core.repository.identity.AccountRepository;

import com.example.epub.api.ApiApplication;
import com.example.epub.core.service.identity.*;
import com.example.epub.core.model.identity.Account;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="EPUB_IDENTITY_DB_TEST",matches="true")
class GoogleAccountsIT {
    @Test void migrationUpgradeIdentityRacesAndEmailCollisionsAreSafe() throws Exception {
        try(var context=new SpringApplicationBuilder(ApiApplication.class).web(WebApplicationType.NONE)
                .run("--spring.profiles.active=local","--app.local-seed.enabled=false","--logging.level.root=WARN","--debug=false")) {
            var jdbc=context.getBean(JdbcTemplate.class);var google=context.getBean(GoogleAccounts.class);
            String schema="google_upgrade_"+UUID.randomUUID().toString().replace("-","");
            var source=context.getBean(DataSource.class);
            try {
                Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                        .target(MigrationVersion.fromVersion("1")).load().migrate();
                jdbc.execute("INSERT INTO "+schema+".users(email_normalized,password_hash,display_name) VALUES ('upgrade@example.test','hash','Upgrade')");
                Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
                assertEquals("hash",jdbc.queryForObject("SELECT password_hash FROM "+schema+".users WHERE email_normalized='upgrade@example.test'",String.class));
                jdbc.execute("INSERT INTO "+schema+".users(email_normalized,password_hash,display_name) VALUES ('google@example.test',NULL,'Google')");
            } finally {jdbc.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
            String subject=UUID.randomUUID().toString(),email=subject+"@gmail.com";
            UUID id;
            try(var executor=Executors.newFixedThreadPool(2)) {
                Callable<Account> login=() -> google.login("https://accounts.google.com",subject,email,true,true,"Reader");
                var outcomes=executor.invokeAll(List.of(login,login));id=outcomes.getFirst().get().id();
                assertEquals(id,outcomes.get(1).get().id());
            }
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_identities WHERE subject=?",Integer.class,subject));
            assertEquals(id,google.login("accounts.google.com",subject,"changed@example.test",true,false,"Changed").id());
            assertEquals(email,context.getBean(AccountRepository.class).byId(id).orElseThrow().email(),"Identity lookup never silently changes email");
            assertThrows(GoogleAccounts.Rejected.class,() -> google.login("https://accounts.google.com",UUID.randomUUID().toString(),email,true,true,"Collision"));
            jdbc.update("UPDATE users SET status='LOCKED' WHERE id=?",id);
            assertThrows(GoogleAccounts.Rejected.class,() -> google.login("https://accounts.google.com",subject,email,true,true,"Reader"));
            var external=google.login("https://accounts.google.com",UUID.randomUUID().toString(),UUID.randomUUID()+"@example.test",true,false,"External");
            assertNull(external.emailVerifiedAt());assertNull(external.passwordHash());
            assertThrows(AccessPolicy.Denied.class,() -> AccessPolicy.requireVerified(external));
            assertThrows(GoogleAccounts.Rejected.class,() -> google.login("https://evil.example",subject,email,true,true,"Reader"));
            assertThrows(GoogleAccounts.Rejected.class,() -> google.login("https://accounts.google.com",UUID.randomUUID().toString(),UUID.randomUUID()+"@gmail.com",false,false,"Reader"));
            jdbc.update("DELETE FROM users WHERE id IN (?,?)",id,external.id());
        }
    }
}
