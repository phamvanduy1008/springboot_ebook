package com.example.epub.core.config;
import com.example.epub.core.integration.storage.ObjectStorage;
import com.example.epub.core.repository.epub.UploadRepository;
import com.example.epub.core.service.epub.UploadService;
import com.example.epub.core.service.identity.AccountService;
import com.example.epub.core.service.jobs.JobService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@AutoConfiguration(after={JobConfiguration.class,StorageConfiguration.class})
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
public class EpubConfiguration {
    @Bean public UploadRepository uploadRepository(JdbcTemplate jdbc) {return new UploadRepository(jdbc);}
    @Bean public UploadService uploadService(UploadRepository uploads,ObjectStorage storage,JobService jobs,AccountService accounts,PlatformTransactionManager manager,Environment env) {
        return new UploadService(uploads,storage,jobs,accounts,new TransactionTemplate(manager),env.getProperty("app.epub.private-quota-bytes",Long.class,1073741824L),env.getProperty("app.epub.admin-quota-bytes",Long.class,107374182400L));
    }
}
