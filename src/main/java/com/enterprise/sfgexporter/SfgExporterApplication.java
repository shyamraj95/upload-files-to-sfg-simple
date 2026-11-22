package com.enterprise.sfgexporter;

import com.enterprise.sfgexporter.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Oracle-to-IBM Sterling File Gateway (SFG) SFTP Exporter worker.
 *
 * <p>Bootstraps Spring Boot without an embedded web server ({@code spring.main.web-application-type=none}),
 * activates Spring's scheduling infrastructure ({@link EnableScheduling}) for both dynamic YAML-driven
 * export jobs and the daily 15-day backup retention sweep, and binds strongly typed configuration from
 * {@code app.*} via {@link AppProperties}.</p>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
public class SfgExporterApplication {

    public static void main(String[] args) {
        SpringApplication.run(SfgExporterApplication.class, args);
    }
}
