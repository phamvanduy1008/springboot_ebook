package com.example.epub.core.repository.catalog;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="EPUB_IDENTITY_DB_TEST",matches="true")
class LegacySchemaIT {
 @Test void explicitBaselinePreservesMetadataAndEnablesCurrentWritesWithoutWeakeningVersions() throws Exception {
  var root=new JdbcTemplate(new DriverManagerDataSource(System.getenv("DB_URL"),System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD")));
  String schema="legacy_test_"+UUID.randomUUID().toString().replace("-","");root.execute("CREATE SCHEMA "+schema);
  String url=System.getenv("DB_URL")+(System.getenv("DB_URL").contains("?")?"&":"?")+"currentSchema="+schema;
  var datasource=new DriverManagerDataSource(url,System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"));var db=new JdbcTemplate(datasource);
  try {
   for(String name:new String[]{"V001__identity.sql","V005__catalog_and_versions.sql"})try(var stream=getClass().getResourceAsStream("/db/migration/"+name)){assertNotNull(stream);db.execute(new String(stream.readAllBytes(),StandardCharsets.UTF_8));}
   db.execute("ALTER TABLE books ALTER COLUMN search_text DROP EXPRESSION; ALTER TABLE books ALTER COLUMN search_text SET DEFAULT ''; DROP FUNCTION catalog_fold_v1(text); ALTER TABLE authors DROP COLUMN revision; ALTER TABLE authors ALTER COLUMN biography DROP NOT NULL; ALTER TABLE categories DROP COLUMN color; ALTER TABLE books DROP COLUMN cover_key");
   db.execute("CREATE TABLE audit_logs(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,actor_id uuid REFERENCES users(id),action varchar(80) NOT NULL,target_type varchar(40),target_id uuid,request_id uuid NOT NULL,safe_metadata jsonb NOT NULL DEFAULT '{}',created_at timestamptz DEFAULT now()); CREATE TABLE book_characters(id uuid PRIMARY KEY,book_id uuid REFERENCES books(id),name varchar(120),role_label varchar(100) NOT NULL,description varchar(1000) NOT NULL DEFAULT '',initials varchar(8),color varchar(7),position integer NOT NULL,revision bigint NOT NULL DEFAULT 0,UNIQUE(book_id,position)); CREATE TABLE book_interactions(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,book_id uuid REFERENCES books(id),kind varchar(8),dedupe_key varchar(64) NOT NULL,happened_at timestamptz NOT NULL DEFAULT now(),UNIQUE(book_id,kind,dedupe_key))");
   UUID book=UUID.randomUUID(),author=UUID.randomUUID();db.update("INSERT INTO books(id,visibility,title,author_display,slug) VALUES (?,'PUBLIC','Đường Cũ','Tác Giả','duong-cu')",book);db.update("INSERT INTO authors(id,name,slug,biography) VALUES (?,'Tác Giả','tac-gia',NULL)",author);db.update("INSERT INTO book_authors(book_id,author_id) VALUES (?,?)",book,author);
   var flyway=Flyway.configure().dataSource(datasource).schemas(schema).defaultSchema(schema).baselineVersion("7").load();flyway.baseline();flyway.migrate();flyway.validate();
   assertEquals(book,db.queryForObject("SELECT id FROM books WHERE title='Đường Cũ'",UUID.class));assertEquals(author,db.queryForObject("SELECT author_id FROM book_authors WHERE book_id=?",UUID.class,book));
   db.update("UPDATE books SET title='Đổi Tên' WHERE id=?",book);assertEquals("doi ten tac gia",db.queryForObject("SELECT search_text FROM books WHERE id=?",String.class,book));
   db.update("INSERT INTO book_interactions(book_id,kind,viewer_hash,occurred_at) VALUES (?,'VIEW',?,TIMESTAMPTZ '2026-01-01 00:00:00Z'),(?,'VIEW',?,TIMESTAMPTZ '2026-01-02 00:00:00Z')",book,"a".repeat(64),book,"a".repeat(64));assertEquals(2,db.queryForObject("SELECT count(*) FROM book_interactions",Integer.class));
   assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("INSERT INTO book_interactions(book_id,kind,viewer_hash,occurred_at) VALUES (?,'VIEW',?,TIMESTAMPTZ '2026-01-01 12:00:00Z')",book,"a".repeat(64)));
   db.update("INSERT INTO audit_logs(action,request_id) VALUES ('TEST','request-test')");db.update("INSERT INTO users(email_normalized,password_hash,display_name) VALUES ('google@example.test',NULL,'Google')");
   assertEquals("BASELINE",db.queryForObject("SELECT type FROM flyway_schema_history WHERE version='7'",String.class));assertEquals(1,db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='008' OR version='8'",Integer.class));
  } finally {root.execute("DROP SCHEMA "+schema+" CASCADE");}
 }
}
