package com.example.epub.core.config;
import com.example.epub.core.repository.catalog.*;
import com.example.epub.core.service.catalog.*;
import com.example.epub.core.service.identity.AccountService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@AutoConfiguration(after=IdentityConfiguration.class)
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
public class CatalogConfiguration {
    @Bean public PublicCatalogService publicCatalogService(CatalogRepository catalog,PlatformTransactionManager manager) {return new PublicCatalogService(catalog,manager);}
    @Bean public CatalogRepository catalogRepository(JdbcTemplate jdbc) {return new CatalogRepository(jdbc);}
    @Bean public CatalogAdminRepository catalogAdminRepository(JdbcTemplate jdbc) {return new CatalogAdminRepository(jdbc);}
    @Bean public CatalogAdminService catalogAdminService(CatalogRepository catalog,CatalogAdminRepository changes,AccountService accounts,PlatformTransactionManager manager) {return new CatalogAdminService(catalog,changes,accounts,new TransactionTemplate(manager));}
}
