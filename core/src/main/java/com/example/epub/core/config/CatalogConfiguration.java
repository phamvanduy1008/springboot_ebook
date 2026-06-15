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
    @Bean public com.example.epub.core.repository.library.LibraryRepository libraryRepository(JdbcTemplate jdbc) {return new com.example.epub.core.repository.library.LibraryRepository(jdbc);}
    @Bean public com.example.epub.core.service.library.LibraryService libraryService(com.example.epub.core.repository.library.LibraryRepository library,AccountService accounts,PlatformTransactionManager manager) {return new com.example.epub.core.service.library.LibraryService(library,accounts,manager,java.time.Clock.systemUTC());}
    @Bean public EditorialRepository editorialRepository(JdbcTemplate jdbc) {return new EditorialRepository(jdbc);}
    @Bean public StoryOverviewService storyOverviewService(CatalogRepository catalog,EditorialRepository editorial,MetricsRepository metrics,PlatformTransactionManager manager) {return new StoryOverviewService(catalog,editorial,metrics,java.time.Clock.systemUTC(),manager);}
    @Bean public RecommendationRepository recommendationRepository(JdbcTemplate jdbc) {return new RecommendationRepository(jdbc);}
    @Bean public RecommendationService recommendationService(RecommendationRepository repository,CatalogRepository catalog,PlatformTransactionManager manager) {return new RecommendationService(repository,catalog,java.time.Clock.systemUTC(),manager);}
    @Bean public MetricsRepository metricsRepository(JdbcTemplate jdbc) {return new MetricsRepository(jdbc);}
    @Bean public RankingService rankingService(CatalogRepository catalog,MetricsRepository metrics,AccountService accounts,PlatformTransactionManager manager) {return new RankingService(catalog,metrics,accounts,java.time.Clock.systemUTC(),manager);}
    @Bean public PublicCatalogService publicCatalogService(CatalogRepository catalog,RankingService rankings,PlatformTransactionManager manager) {return new PublicCatalogService(catalog,rankings,manager);}
    @Bean @org.springframework.context.annotation.Primary public CatalogRepository catalogRepository(JdbcTemplate jdbc) {return new CatalogRepository(jdbc);}
    @Bean public CatalogAdminRepository catalogAdminRepository(JdbcTemplate jdbc) {return new CatalogAdminRepository(jdbc);}
    @Bean public CatalogAdminService catalogAdminService(CatalogRepository catalog,CatalogAdminRepository changes,AccountService accounts,PlatformTransactionManager manager,EditorialRepository editorial) {return new CatalogAdminService(catalog,changes,accounts,new TransactionTemplate(manager),editorial);}
}
