package com.medha.applicationmonitoring.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record OrderRequest(

        @NotBlank(message = "customerName is required")
        String customerName,

        @NotBlank(message = "product is required")
        String product,

        @NotNull(message = "quantity is required")
        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 1000, message = "quantity must not exceed 1000")
        Integer quantity,

        @NotNull(message = "unitPrice is required")
        @DecimalMin(value = "0.01", message = "unitPrice must be positive")
        BigDecimal unitPrice
) {
}
