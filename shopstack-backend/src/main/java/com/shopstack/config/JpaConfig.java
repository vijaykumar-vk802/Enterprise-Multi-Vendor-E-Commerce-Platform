package com.shopstack.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * JPA Configuration for Spring Data Repositories.
 * Configures entity manager factory, transaction manager, and repository scanning.
 */
@Configuration
@EnableJpaRepositories(
    basePackages = {"com.shopstack.repository"},
    entityManagerFactoryRef = "entityManagerFactory",
    transactionManagerRef = "transactionManager"
)
@EntityScan(basePackages = {"com.shopstack.entity", "com.shopstack.model"})
@EnableTransactionManagement
public class JpaConfig {
    // Spring Boot auto-configures entityManagerFactory and transactionManager
    // based on application.yml configuration
}
