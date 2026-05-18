package com.example.epub.core.repository.jobs;
import com.example.epub.core.service.jobs.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="EPUB_IDENTITY_DB_TEST",matches="true")
class JobQueueIT {
    @Test void concurrentDedupeClaimsCrashRecoveryAndFencedResults() throws Exception {
        var root=new DriverManagerDataSource(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"));
        var admin=new JdbcTemplate(root);String schema="jobs_test_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
        var ds=new DriverManagerDataSource(System.getenv("DB_URL")+(System.getenv("DB_URL").contains("?")?"&":"?")+"currentSchema="+schema,System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"));
        var db=new JdbcTemplate(ds);
        try {
            Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).load().migrate();
            var repository=new JobRepository(db);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));var service=new JobService(repository,null,tx);
            UUID owner=UUID.randomUUID(),other=UUID.randomUUID();
            for(UUID id:List.of(owner,other))db.update("INSERT INTO users(id,email_normalized,password_hash,display_name) VALUES (?,?,'unused','job test')",id,id+"@example.test");
            try(var workers=Executors.newFixedThreadPool(2)) {
                var first=workers.submit(()->repository.enqueue(owner,null,null,"INGEST","concurrent"));
                var second=workers.submit(()->repository.enqueue(owner,null,null,"INGEST","concurrent"));
                UUID id=first.get(10,TimeUnit.SECONDS);assertEquals(id,second.get(10,TimeUnit.SECONDS));
                assertTrue(repository.visible(id,owner,false).isPresent());assertTrue(repository.visible(id,other,false).isEmpty());assertTrue(repository.visible(id,other,true).isEmpty());
                var a=workers.submit(()->repository.claim("INGEST",90));var b=workers.submit(()->repository.claim("INGEST",90));
                var x=a.get(10,TimeUnit.SECONDS);var y=b.get(10,TimeUnit.SECONDS);assertNotEquals(x.isPresent(),y.isPresent());var old=x.orElseGet(y::orElseThrow);
                assertTrue(repository.heartbeat(old,90));
                db.update("UPDATE jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",id);
                assertFalse(repository.heartbeat(old,90));assertFalse(repository.succeed(old));assertFalse(repository.fail(old,"SAFE_ERROR"));
                assertEquals(1,repository.sweep());assertEquals(0,repository.sweep());
                db.update("UPDATE jobs SET available_at=clock_timestamp() WHERE id=?",id);
                var fresh=repository.claim("INGEST",90).orElseThrow();assertNotEquals(old.token(),fresh.token());
                assertThrows(JobService.LeaseLost.class,()->service.complete(old,()->db.update("UPDATE users SET display_name='stale result' WHERE id=?",owner)));
                assertEquals("job test",db.queryForObject("SELECT display_name FROM users WHERE id=?",String.class,owner));
                service.complete(fresh,()->db.update("UPDATE users SET display_name='new result' WHERE id=?",owner));assertEquals("SUCCEEDED",repository.visible(id,owner,false).orElseThrow().status());
                assertFalse(repository.succeed(fresh));
                var failing=repository.enqueue(owner,null,null,"INGEST","failing");db.update("UPDATE jobs SET max_attempts=1 WHERE id=?",failing);
                var terminal=repository.claim("INGEST",90).orElseThrow();assertTrue(repository.fail(terminal,"EPUB_UNSUPPORTED"));assertEquals("FAILED",repository.visible(failing,owner,false).orElseThrow().status());
                assertThrows(IllegalArgumentException.class,()->repository.fail(terminal,"secret/path"));
            }
        } finally {admin.execute("DROP SCHEMA "+schema+" CASCADE");}
    }
}
