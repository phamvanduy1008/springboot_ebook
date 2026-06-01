package com.example.epub.core.service.jobs;
import com.example.epub.core.model.jobs.JobModels.*;
import com.example.epub.core.repository.jobs.JobRepository;
import com.example.epub.core.service.identity.*;
import com.example.epub.core.service.catalog.CatalogFailure;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;

public class JobService {
    public static final class LeaseLost extends RuntimeException {public LeaseLost() {super("Job lease lost");}}
    private final JobRepository jobs;private final AccountService accounts;private final TransactionTemplate transactions;
    public JobService(JobRepository jobs,AccountService accounts,TransactionTemplate transactions) {this.jobs=jobs;this.accounts=accounts;this.transactions=transactions;transactions.setTimeout(120);}
    public UUID enqueue(UUID owner,UUID book,UUID version,String type,String dedupe) {return jobs.enqueue(owner,book,version,type,dedupe);}
    public Optional<Lease> claim(String type) {return jobs.claim(type,90);}
    public boolean heartbeat(Lease lease) {return jobs.heartbeat(lease,90);}
    public int sweep() {return jobs.sweep();}
    public void fail(Lease lease,String code) {fail(lease,code,true,()->{});}
    public void fail(Lease lease,String code,boolean retry,Runnable persistFailure) {
        transactions.executeWithoutResult(tx->{if(!jobs.lock(lease))throw new LeaseLost();if(!jobs.fail(lease,code,retry))throw new LeaseLost();persistFailure.run();});
    }
    public void complete(Lease lease,Runnable persistResult) {
        transactions.executeWithoutResult(tx->{
            if(!jobs.lock(lease))throw new LeaseLost();
            persistResult.run();
            if(!jobs.succeed(lease))throw new LeaseLost(); // rolls back results as well
        });
    }
    public Status status(UUID actor,UUID id) {
        var account=accounts.byId(actor).orElse(null);if(!AccessPolicy.active(account))throw new AccessPolicy.Denied(false);
        return jobs.visible(id,actor,"ADMIN".equals(account.role())).orElseThrow(()->new CatalogFailure(CatalogFailure.Reason.NOT_FOUND));
    }
}
