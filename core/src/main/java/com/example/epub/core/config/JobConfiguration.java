package com.example.epub.core.config;
import com.example.epub.core.repository.jobs.JobRepository;
import com.example.epub.core.service.jobs.JobService;
import com.example.epub.core.service.identity.AccountService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@AutoConfiguration(after=IdentityConfiguration.class)
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
public class JobConfiguration {
    @Bean public JobRepository jobRepository(JdbcTemplate jdbc) {return new JobRepository(jdbc);}
    @Bean public JobService jobService(JobRepository jobs,AccountService accounts,PlatformTransactionManager manager) {return new JobService(jobs,accounts,new TransactionTemplate(manager));}
}
