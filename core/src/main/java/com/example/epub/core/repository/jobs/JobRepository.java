package com.example.epub.core.repository.jobs;
import com.example.epub.core.model.jobs.JobModels.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

public class JobRepository {
    private final JdbcTemplate jdbc;
    public JobRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public UUID enqueue(UUID owner,UUID book,UUID version,String type,String dedupe) {
        if(!Set.of("INGEST","TTS","CLEANUP").contains(type)||dedupe==null||dedupe.isBlank()||dedupe.length()>200)throw new IllegalArgumentException("Invalid job identity");
        return jdbc.queryForObject("""
            INSERT INTO jobs(owner_id,book_id,book_version_id,type,dedupe_key) VALUES (?,?,?,?,?)
            ON CONFLICT(dedupe_key) DO UPDATE SET dedupe_key=excluded.dedupe_key
            RETURNING id
            """,UUID.class,owner,book,version,type,dedupe);
    }
    public Optional<Lease> claim(String type,int seconds) {
        if(seconds<5||seconds>3600)throw new IllegalArgumentException("Invalid lease duration");
        return jdbc.query("""
            WITH candidate AS (SELECT id FROM jobs WHERE status='QUEUED' AND type=?
              AND available_at<=clock_timestamp() AND attempts<max_attempts
              ORDER BY priority DESC,available_at,id FOR UPDATE SKIP LOCKED LIMIT 1)
            UPDATE jobs j SET status='RUNNING',attempts=attempts+1,lease_token=?,
              lease_until=clock_timestamp()+make_interval(secs=>?),updated_at=clock_timestamp()
            FROM candidate c WHERE j.id=c.id
            RETURNING j.id,j.lease_token,j.owner_id,j.book_id,j.book_version_id,j.type,j.attempts
            """,(rs,row)->new Lease(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getObject(5,UUID.class),rs.getString(6),rs.getInt(7)),type,UUID.randomUUID(),seconds).stream().findFirst();
    }
    public boolean heartbeat(Lease job,int seconds) {
        if(seconds<5||seconds>3600)throw new IllegalArgumentException("Invalid lease duration");
        return jdbc.update("UPDATE jobs SET lease_until=clock_timestamp()+make_interval(secs=>?),updated_at=clock_timestamp() WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>clock_timestamp()",seconds,job.id(),job.token())==1;
    }
    public boolean lock(Lease job) {
        return !jdbc.queryForList("SELECT id FROM jobs WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>clock_timestamp() FOR UPDATE",job.id(),job.token()).isEmpty();
    }
    public boolean succeed(Lease job) {
        return jdbc.update("UPDATE jobs SET status='SUCCEEDED',lease_token=NULL,lease_until=NULL,last_error_code=NULL,updated_at=clock_timestamp() WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>clock_timestamp()",job.id(),job.token())==1;
    }
    public boolean fail(Lease job,String safeCode) {
        return fail(job,safeCode,true);
    }
    public boolean fail(Lease job,String safeCode,boolean retry) {
        if(safeCode==null||!safeCode.matches("[A-Z][A-Z0-9_]{0,79}"))throw new IllegalArgumentException("Safe error code required");
        return jdbc.update("""
            UPDATE jobs SET status=CASE WHEN NOT ? OR attempts>=max_attempts THEN 'FAILED' ELSE 'QUEUED' END,
              available_at=clock_timestamp()+make_interval(secs=>least(300,5*attempts*attempts)),
              lease_token=NULL,lease_until=NULL,last_error_code=?,updated_at=clock_timestamp()
            WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>clock_timestamp()
            """,retry,safeCode,job.id(),job.token())==1;
    }
    public int sweep() {
        return jdbc.update("""
            UPDATE jobs SET status=CASE WHEN attempts>=max_attempts THEN 'FAILED' ELSE 'QUEUED' END,
              available_at=clock_timestamp()+make_interval(secs=>least(300,5*attempts*attempts)),
              lease_token=NULL,lease_until=NULL,last_error_code='LEASE_EXPIRED',updated_at=clock_timestamp()
            WHERE status='RUNNING' AND lease_until<=clock_timestamp()
            """);
    }
    public Optional<Status> visible(UUID id,UUID actor,boolean admin) {
        // Admins can inspect public jobs and their own jobs; never another owner's private job.
        return jdbc.query("""
            SELECT j.* FROM jobs j LEFT JOIN books b ON b.id=j.book_id
            WHERE j.id=? AND (j.owner_id=? OR (? AND j.owner_id IS NULL AND (b.visibility='PUBLIC' OR j.book_id IS NULL)))
            """,(rs,row)->new Status(rs.getObject("id",UUID.class),rs.getObject("book_id",UUID.class),rs.getObject("book_version_id",UUID.class),rs.getString("type"),rs.getString("status"),rs.getInt("attempts"),rs.getInt("max_attempts"),safeError(rs.getString("last_error_code")),rs.getTimestamp("updated_at").toInstant()),id,actor,admin).stream().findFirst();
    }
    public Page adminPage(UUID actor,String status,String type,int page,int size) {
        String where=" FROM jobs j LEFT JOIN books b ON b.id=j.book_id WHERE (j.owner_id=? OR (j.owner_id IS NULL AND (b.visibility='PUBLIC' OR j.book_id IS NULL)))";
        var args=new ArrayList<Object>();args.add(actor);
        if(status!=null) {where+=" AND j.status=?";args.add(status);}
        if(type!=null) {where+=" AND j.type=?";args.add(type);}
        long total=jdbc.queryForObject("SELECT count(*)"+where,Long.class,args.toArray());
        var listArgs=new ArrayList<>(args);listArgs.add(size);listArgs.add((long)page*size);
        var items=jdbc.query("SELECT j.*"+where+" ORDER BY j.updated_at DESC,j.id DESC LIMIT ? OFFSET ?",
            (rs,row)->new Status(rs.getObject("id",UUID.class),rs.getObject("book_id",UUID.class),rs.getObject("book_version_id",UUID.class),rs.getString("type"),rs.getString("status"),rs.getInt("attempts"),rs.getInt("max_attempts"),safeError(rs.getString("last_error_code")),rs.getTimestamp("updated_at").toInstant()),listArgs.toArray());
        return new Page(items,page,size,total);
    }
    private static String safeError(String code) {return code==null?null:code.matches("[A-Z][A-Z0-9_]{0,79}")?code:"JOB_PROCESSING_FAILED";}
}
