package com.medha.applicationmonitoring.service;

import com.medha.applicationmonitoring.domain.Order;
import com.medha.applicationmonitoring.domain.OrderStatus;
import com.medha.applicationmonitoring.dto.OrderRequest;
import com.medha.applicationmonitoring.dto.OrderResponse;
import com.medha.applicationmonitoring.dto.OrderStatusUpdateRequest;
import com.medha.applicationmonitoring.exception.InvalidOrderStateTransitionException;
import com.medha.applicationmonitoring.exception.OrderNotFoundException;
import com.medha.applicationmonitoring.repository.OrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    private MeterRegistry meterRegistry;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        orderService = new OrderService(orderRepository, meterRegistry);
    }

    private Order sampleOrder(Long id, OrderStatus status) {
        Instant now = Instant.now();
        return Order.builder()
                .id(id)
                .customerName("Alice")
                .product("Widget")
                .quantity(2)
                .unitPrice(BigDecimal.valueOf(10))
                .totalAmount(BigDecimal.valueOf(20))
                .status(status)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    @Test
    void createOrder_savesEntityAndIncrementsCounter() {
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(1L);
            o.setCreatedAt(Instant.now());
            o.setUpdatedAt(Instant.now());
            return o;
        });

        OrderRequest request = new OrderRequest("Alice", "Widget", 2, BigDecimal.valueOf(10));
        OrderResponse response = orderService.createOrder(request);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.totalAmount()).isEqualByComparingTo("20");
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);

        assertThat(meterRegistry.get("orders_creation_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("order_value_amount").summary().count()).isEqualTo(1);
    }

    @Test
    void getOrder_whenMissing_throwsNotFound() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrder(99L))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void updateStatus_validTransition_incrementsFailedCounter() {
        Order order = sampleOrder(1L, OrderStatus.PROCESSING);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = orderService.updateStatus(1L, new OrderStatusUpdateRequest(OrderStatus.FAILED));

        assertThat(response.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(meterRegistry.get("orders_failed_total").counter().count()).isEqualTo(1.0);
        verify(orderRepository).save(order);
    }

    @Test
    void updateStatus_invalidTransition_throwsAndDoesNotSave() {
        Order order = sampleOrder(1L, OrderStatus.COMPLETED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.updateStatus(1L, new OrderStatusUpdateRequest(OrderStatus.PROCESSING)))
                .isInstanceOf(InvalidOrderStateTransitionException.class);
    }

    @Test
    void cancelOrder_fromPending_incrementsCancelledCounter() {
        Order order = sampleOrder(1L, OrderStatus.PENDING);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = orderService.cancelOrder(1L);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(meterRegistry.get("orders_cancelled_total").counter().count()).isEqualTo(1.0);
    }
}
