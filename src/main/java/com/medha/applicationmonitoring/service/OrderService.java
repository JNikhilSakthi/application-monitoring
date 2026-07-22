package com.medha.applicationmonitoring.service;

import com.medha.applicationmonitoring.domain.Order;
import com.medha.applicationmonitoring.domain.OrderStatus;
import com.medha.applicationmonitoring.dto.OrderRequest;
import com.medha.applicationmonitoring.dto.OrderResponse;
import com.medha.applicationmonitoring.dto.OrderStatusUpdateRequest;
import com.medha.applicationmonitoring.dto.PageResponse;
import com.medha.applicationmonitoring.exception.InvalidOrderStateTransitionException;
import com.medha.applicationmonitoring.exception.OrderNotFoundException;
import com.medha.applicationmonitoring.repository.OrderRepository;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Order processing business logic, instrumented end-to-end with Micrometer so every business
 * event is observable in Prometheus/Grafana:
 *
 * <ul>
 *   <li>{@code orders_created_total} - counter, tagged by outcome (created/failed)</li>
 *   <li>{@code orders_active} - gauge, current count of orders not yet in a terminal state</li>
 *   <li>{@code order_value_amount} - distribution summary of order monetary value</li>
 *   <li>{@code order_processing_time_seconds} - timer around the simulated processing step</li>
 *   <li>{@code order_service_*_seconds} - method-level timers via {@code @Timed}</li>
 * </ul>
 */
@Slf4j
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final MeterRegistry meterRegistry;

    private final Counter ordersCreatedCounter;
    private final Counter ordersFailedCounter;
    private final Counter ordersCancelledCounter;
    private final DistributionSummary orderValueSummary;
    private final Timer orderProcessingTimer;

    public OrderService(OrderRepository orderRepository, MeterRegistry meterRegistry) {
        this.orderRepository = orderRepository;
        this.meterRegistry = meterRegistry;

        this.ordersCreatedCounter = Counter.builder("orders_created_total")
                .description("Total number of orders successfully created")
                .register(meterRegistry);

        this.ordersFailedCounter = Counter.builder("orders_failed_total")
                .description("Total number of orders that ended in FAILED status")
                .register(meterRegistry);

        this.ordersCancelledCounter = Counter.builder("orders_cancelled_total")
                .description("Total number of orders cancelled by a client")
                .register(meterRegistry);

        this.orderValueSummary = DistributionSummary.builder("order_value_amount")
                .description("Distribution of order total amounts")
                .baseUnit("currency")
                .publishPercentileHistogram()
                .register(meterRegistry);

        this.orderProcessingTimer = Timer.builder("order_processing_time_seconds")
                .description("Time taken to move an order from PENDING to a terminal state")
                .publishPercentileHistogram()
                .register(meterRegistry);

        // Gauge backed by a live repository query - reflects true DB state, not an in-memory
        // counter that could drift after a restart or when multiple instances share the DB.
        meterRegistry.gauge("orders_active", this, OrderService::countActiveOrders);
    }

    @Timed(value = "order_service_create", description = "Time to create an order")
    @Transactional
    public OrderResponse createOrder(OrderRequest request) {
        BigDecimal totalAmount = request.unitPrice().multiply(BigDecimal.valueOf(request.quantity()));

        Order order = Order.builder()
                .customerName(request.customerName())
                .product(request.product())
                .quantity(request.quantity())
                .unitPrice(request.unitPrice())
                .totalAmount(totalAmount)
                .status(OrderStatus.PENDING)
                .build();

        Order saved = orderRepository.save(order);
        ordersCreatedCounter.increment();
        orderValueSummary.record(totalAmount.doubleValue());
        log.info("Created order {} for customer {} amount {}", saved.getId(), saved.getCustomerName(), totalAmount);
        return OrderResponse.from(saved);
    }

    @Timed(value = "order_service_get", description = "Time to fetch a single order")
    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long id) {
        return OrderResponse.from(findOrderOrThrow(id));
    }

    @Timed(value = "order_service_list", description = "Time to list orders")
    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> listOrders(OrderStatus status, Pageable pageable) {
        Page<Order> page = status != null
                ? orderRepository.findByStatus(status, pageable)
                : orderRepository.findAll(pageable);
        return PageResponse.from(page.map(OrderResponse::from));
    }

    @Timed(value = "order_service_update_status", description = "Time to transition an order status")
    @Transactional
    public OrderResponse updateStatus(Long id, OrderStatusUpdateRequest request) {
        Order order = findOrderOrThrow(id);
        OrderStatus from = order.getStatus();
        OrderStatus to = request.status();

        validateTransition(from, to);

        Timer.Sample sample = Timer.start(meterRegistry);
        order.setStatus(to);
        Order saved = orderRepository.save(order);
        sample.stop(orderProcessingTimer);

        if (to == OrderStatus.FAILED) {
            ordersFailedCounter.increment();
        } else if (to == OrderStatus.CANCELLED) {
            ordersCancelledCounter.increment();
        }

        log.info("Order {} transitioned {} -> {}", id, from, to);
        return OrderResponse.from(saved);
    }

    @Timed(value = "order_service_cancel", description = "Time to cancel an order")
    @Transactional
    public OrderResponse cancelOrder(Long id) {
        return updateStatus(id, new OrderStatusUpdateRequest(OrderStatus.CANCELLED));
    }

    long countActiveOrders() {
        return orderRepository.countByStatus(OrderStatus.PENDING)
                + orderRepository.countByStatus(OrderStatus.PROCESSING);
    }

    private Order findOrderOrThrow(Long id) {
        return orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(OrderStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(OrderStatus.PENDING, Set.of(OrderStatus.PROCESSING, OrderStatus.CANCELLED));
        ALLOWED_TRANSITIONS.put(OrderStatus.PROCESSING, Set.of(OrderStatus.COMPLETED, OrderStatus.FAILED, OrderStatus.CANCELLED));
        ALLOWED_TRANSITIONS.put(OrderStatus.COMPLETED, Set.of());
        ALLOWED_TRANSITIONS.put(OrderStatus.FAILED, Set.of());
        ALLOWED_TRANSITIONS.put(OrderStatus.CANCELLED, Set.of());
    }

    private void validateTransition(OrderStatus from, OrderStatus to) {
        if (from == to) {
            return;
        }
        Set<OrderStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new InvalidOrderStateTransitionException(from, to);
        }
    }
}
