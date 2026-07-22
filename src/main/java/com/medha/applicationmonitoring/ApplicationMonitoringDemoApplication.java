package com.medha.applicationmonitoring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application Monitoring Dashboard demo.
 *
 * <p>The domain (a simple order-processing API) exists purely as a vehicle to produce
 * realistic, varied metrics. The star of this project is the Micrometer + Prometheus +
 * Grafana observability pipeline wired around it: custom business metrics are exposed on
 * {@code /actuator/prometheus}, scraped by Prometheus, and visualized in a provisioned
 * Grafana dashboard.</p>
 */
@SpringBootApplication
@EnableScheduling
public class ApplicationMonitoringDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApplicationMonitoringDemoApplication.class, args);
    }
}
