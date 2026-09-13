package com.shopstack.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * JPA Configuration for Spring Data Repositories.
 * 
 * This configuration:
 * - Enables JPA repositories scanning
 * - Configures entity manager factory and transaction manager references
 * - Enables transaction management
 * - Scans for entities in specified packages
 * 
 * Spring Boot auto-configures the entityManagerFactory and transactionManager
 * beans based on datasource and application.yml JPA configuration.
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
    // Configuration is complete - Spring Boot handles bean creation
}
