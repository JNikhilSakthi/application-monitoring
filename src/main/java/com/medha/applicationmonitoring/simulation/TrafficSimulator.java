package com.medha.applicationmonitoring.simulation;

import com.medha.applicationmonitoring.domain.OrderStatus;
import com.medha.applicationmonitoring.dto.OrderRequest;
import com.medha.applicationmonitoring.dto.OrderResponse;
import com.medha.applicationmonitoring.dto.OrderStatusUpdateRequest;
import com.medha.applicationmonitoring.exception.InvalidOrderStateTransitionException;
import com.medha.applicationmonitoring.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates continuous synthetic order traffic so that, out of the box, {@code docker-compose up}
 * produces a live, moving Grafana dashboard instead of a flat line. Purely a demo convenience -
 * disable with {@code monitoring.simulation.enabled=false} (e.g. during tests) to keep the
 * dataset deterministic.
 */
@Slf4j
@Component
public class TrafficSimulator {

    private static final List<String> PRODUCTS = List.of("Widget", "Gadget", "Gizmo", "Doohickey", "Thingamajig");
    private static final List<String> CUSTOMERS = List.of("Alice", "Bob", "Carla", "Dev", "Elena", "Farid");

    private final OrderService orderService;
    private final boolean enabled;

    public TrafficSimulator(OrderService orderService,
                             @Value("${monitoring.simulation.enabled:true}") boolean enabled) {
        this.orderService = orderService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${monitoring.simulation.create-interval-ms:1500}")
    public void simulateOrderCreation() {
        if (!enabled) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        OrderRequest request = new OrderRequest(
                CUSTOMERS.get(random.nextInt(CUSTOMERS.size())),
                PRODUCTS.get(random.nextInt(PRODUCTS.size())),
                random.nextInt(1, 10),
                BigDecimal.valueOf(random.nextDouble(5, 250)).setScale(2, java.math.RoundingMode.HALF_UP));
        try {
            orderService.createOrder(request);
        } catch (Exception ex) {
            log.warn("Simulated order creation failed: {}", ex.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${monitoring.simulation.progress-interval-ms:2500}")
    public void simulateOrderProgress() {
        if (!enabled) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        var pending = orderService.listOrders(OrderStatus.PENDING, org.springframework.data.domain.PageRequest.of(0, 5));
        for (OrderResponse order : pending.content()) {
            try {
                orderService.updateStatus(order.id(), new OrderStatusUpdateRequest(OrderStatus.PROCESSING));
            } catch (InvalidOrderStateTransitionException ignored) {
                // Another simulator tick already moved it on; safe to ignore.
            }
        }

        var processing = orderService.listOrders(OrderStatus.PROCESSING, org.springframework.data.domain.PageRequest.of(0, 5));
        for (OrderResponse order : processing.content()) {
            // ~10% simulated failure rate keeps orders_failed_total non-zero for dashboard demo purposes.
            OrderStatus next = random.nextInt(100) < 10 ? OrderStatus.FAILED : OrderStatus.COMPLETED;
            try {
                orderService.updateStatus(order.id(), new OrderStatusUpdateRequest(next));
            } catch (InvalidOrderStateTransitionException ignored) {
                // Concurrent tick already transitioned this order.
            }
        }
    }
}
