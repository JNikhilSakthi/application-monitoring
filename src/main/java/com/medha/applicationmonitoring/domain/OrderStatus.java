package com.medha.applicationmonitoring.domain;

/**
 * Lifecycle states of an {@link Order}. Transitions are enforced in the service layer and
 * every transition is reflected in the {@code orders_created_total} / {@code orders_failed_total}
 * / {@code orders_active} custom metrics so dashboards can show throughput and error rate.
 */
public enum OrderStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED
}
