package com.example.epub.worker;
import com.example.epub.core.service.jobs.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import jakarta.annotation.PreDestroy;

@Configuration(proxyBeanMethods=false) @EnableScheduling
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
public class JobRunner {
    private final JobService jobs;private final ObjectProvider<JobHandler> handlers;
    private final AtomicBoolean stopping=new AtomicBoolean();
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"job-heartbeat");t.setDaemon(true);return t;});
    public JobRunner(JobService jobs,ObjectProvider<JobHandler> handlers) {this.jobs=jobs;this.handlers=handlers;}
    @Bean public ThreadPoolTaskScheduler taskScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setWaitForTasksToCompleteOnShutdown(true);scheduler.setAwaitTerminationSeconds(30);return scheduler;
    }
    @Scheduled(fixedDelay=1000) public void poll() {
        if(stopping.get())return;
        jobs.sweep();
        for(var handler:handlers.orderedStream().toList()) {
            if(stopping.get())return;
            var claimed=jobs.claim(handler.type());if(claimed.isEmpty())continue;
            var lease=claimed.get();
            var pulse=heartbeat.scheduleAtFixedRate(()->{try {jobs.heartbeat(lease);} catch(RuntimeException ignored) { /* result transaction checks the DB lease */ }},20,20,TimeUnit.SECONDS);
            try {var result=handler.prepare(lease);if(!stopping.get())jobs.complete(lease,result);}
            catch(JobService.LeaseLost ignored) { /* A replacement worker owns the result. */ }
            catch(Exception failure) {jobs.fail(lease,"JOB_PROCESSING_FAILED");} // never persist raw exception/path/payload
            finally {pulse.cancel(false);}
        }
    }
    @PreDestroy public void stop() {stopping.set(true);heartbeat.shutdown();}
}
