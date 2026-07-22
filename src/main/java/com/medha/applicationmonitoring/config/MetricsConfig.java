package com.medha.applicationmonitoring.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cross-cutting Micrometer configuration.
 *
 * <p>{@link TimedAspect} activates the {@code @Timed} annotation used on
 * {@link com.medha.applicationmonitoring.service.OrderService} methods so per-method latency
 * histograms show up as {@code order_service_*_seconds} in Prometheus.</p>
 *
 * <p>The {@link MeterRegistryCustomizer} stamps every metric emitted by this instance with an
 * {@code application} tag so a single Prometheus/Grafana stack could aggregate metrics from
 * multiple services without name collisions - a common real-world requirement even though this
 * demo only runs one service.</p>
 */
@Configuration
public class MetricsConfig {

    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config()
                .commonTags("application", "springboot-monitoring-demo")
                .meterFilter(MeterFilter.deny(id -> {
                    String uri = id.getTag("uri");
                    return uri != null && (uri.startsWith("/actuator") && !uri.equals("/actuator/prometheus"));
                }));
    }
}
