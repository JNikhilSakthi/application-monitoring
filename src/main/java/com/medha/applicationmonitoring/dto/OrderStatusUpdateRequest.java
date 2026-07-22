package com.medha.applicationmonitoring.dto;

import com.medha.applicationmonitoring.domain.OrderStatus;
import jakarta.validation.constraints.NotNull;

public record OrderStatusUpdateRequest(
        @NotNull(message = "status is required")
        OrderStatus status
) {
}
