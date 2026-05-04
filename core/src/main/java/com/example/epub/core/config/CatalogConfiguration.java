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
    @Bean public EditorialRepository editorialRepository(JdbcTemplate jdbc) {return new EditorialRepository(jdbc);}
    @Bean public StoryOverviewService storyOverviewService(CatalogRepository catalog,EditorialRepository editorial,MetricsRepository metrics,PlatformTransactionManager manager) {return new StoryOverviewService(catalog,editorial,metrics,java.time.Clock.systemUTC(),manager);}
    @Bean public MetricsRepository metricsRepository(JdbcTemplate jdbc) {return new MetricsRepository(jdbc);}
    @Bean public RankingService rankingService(CatalogRepository catalog,MetricsRepository metrics,AccountService accounts,PlatformTransactionManager manager) {return new RankingService(catalog,metrics,accounts,java.time.Clock.systemUTC(),manager);}
    @Bean public PublicCatalogService publicCatalogService(CatalogRepository catalog,RankingService rankings,PlatformTransactionManager manager) {return new PublicCatalogService(catalog,rankings,manager);}
    @Bean public CatalogRepository catalogRepository(JdbcTemplate jdbc) {return new CatalogRepository(jdbc);}
    @Bean public CatalogAdminRepository catalogAdminRepository(JdbcTemplate jdbc) {return new CatalogAdminRepository(jdbc);}
    @Bean public CatalogAdminService catalogAdminService(CatalogRepository catalog,CatalogAdminRepository changes,AccountService accounts,PlatformTransactionManager manager,EditorialRepository editorial) {return new CatalogAdminService(catalog,changes,accounts,new TransactionTemplate(manager),editorial);}
}
