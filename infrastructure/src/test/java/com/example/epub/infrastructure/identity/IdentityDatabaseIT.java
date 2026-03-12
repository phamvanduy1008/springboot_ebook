package com.example.epub.infrastructure.identity;

import com.example.epub.application.identity.UserAccounts;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="EPUB_IDENTITY_DB_TEST", matches="true")
class IdentityDatabaseIT {
    @SpringBootConfiguration @EnableAutoConfiguration
    static class TestApplication { }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(TestApplication.class).web(org.springframework.boot.WebApplicationType.NONE)
                .run("--spring.profiles.active=database-test", "--spring.config.import=classpath:epub-runtime.properties",
                        "--debug=false", "--logging.level.root=WARN");
    }

    @Test void migratesValidatesConstraintsAndPersistsJpaAndSessionsAcrossRestart() throws Exception {
        String sessionId;
        UUID userId=UUID.randomUUID();
        try (var context=start()) {
            var jdbc=context.getBean(JdbcTemplate.class);
            var datasource=context.getBean(DataSource.class);
            context.getBean(Flyway.class).validate();
            assertEquals(1, jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from users", Integer.class), "No automatic demo/admin seed");
            jdbc.update("insert into users(id,email_normalized,password_hash,display_name) values (?,?,?,?)",userId,"reader@example.test","test-hash","Reader");
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("insert into users(email_normalized,password_hash,display_name) values ('reader@example.test','hash','Duplicate')"));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("insert into users(email_normalized,password_hash,display_name) values ('Mixed@Example.test','hash','Mixed')"));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("insert into users(email_normalized,password_hash,display_name,role) values ('bad@example.test','hash','Bad','ROOT')"));
            var user=context.getBean(UserAccounts.class).findByNormalizedEmail("reader@example.test").orElseThrow();
            assertEquals(userId,user.id()); assertEquals("USER",user.role()); assertEquals("ACTIVE",user.status());
            assertFalse(user.toString().contains("test-hash"));
            var repository=sessionRepository(datasource);
            sessionId=createAndSave(repository);
            // Execute the schema bundled with pinned Spring Session 4.1.1 in an isolated schema,
            // then compare column definitions instead of trusting the baseline's old comment.
            try (var connection=datasource.getConnection();var statement=connection.createStatement()) {
                statement.execute("create schema session_reference");statement.execute("set search_path to session_reference");
                ScriptUtils.executeSqlScript(connection,new ClassPathResource("org/springframework/session/jdbc/schema-postgresql.sql"));
                for (String table:new String[]{"spring_session","spring_session_attributes"}) {
                    assertEquals(columns(connection,"session_reference",table),columns(connection,"public",table));
                }
                statement.execute("set search_path to public");statement.execute("drop schema session_reference cascade");
            }
        }
        try (var context=start()) {
            context.getBean(Flyway.class).validate();
            assertEquals(userId,context.getBean(UserAccounts.class).findByNormalizedEmail("reader@example.test").orElseThrow().id());
            var repository=sessionRepository(context.getBean(DataSource.class));
            assertEquals("survives restart",repository.findById(sessionId).getAttribute("verification"));
            repository.deleteById(sessionId);assertNull(repository.findById(sessionId));
        }
        try (var context=new SpringApplicationBuilder(TestApplication.class).web(org.springframework.boot.WebApplicationType.NONE)
                .run("--spring.profiles.active=local", "--spring.config.import=classpath:epub-runtime.properties",
                        "--app.local-seed.enabled=true", "--SEED_EMAIL=seed@example.test", "--SEED_PASSWORD=local-test-password",
                        "--debug=false", "--logging.level.root=WARN")) {
            var seeded=context.getBean(UserAccounts.class).findByNormalizedEmail("seed@example.test").orElseThrow();
            assertEquals("USER",seeded.role());
            assertTrue(new BCryptPasswordEncoder().matches("local-test-password",seeded.passwordHash()));
            assertNotEquals("local-test-password",seeded.passwordHash());
        }
    }
    private static SessionRepository<? extends Session> sessionRepository(DataSource datasource) {
        return new JdbcIndexedSessionRepository(new JdbcTemplate(datasource),new TransactionTemplate(new DataSourceTransactionManager(datasource)));
    }
    private static <S extends Session> String createAndSave(SessionRepository<S> repository) {
        S session=repository.createSession();session.setAttribute("verification","survives restart");repository.save(session);return session.getId();
    }
    private static Map<String,String> columns(java.sql.Connection connection,String schema,String table) throws Exception {
        Map<String,String> result=new HashMap<>();
        try (var rows=connection.getMetaData().getColumns(null,schema,table,null)) {
            while(rows.next())result.put(rows.getString("COLUMN_NAME"),rows.getString("TYPE_NAME")+":"+rows.getInt("COLUMN_SIZE")+":"+rows.getInt("NULLABLE"));
        }
        assertFalse(result.isEmpty());return result;
    }
}
