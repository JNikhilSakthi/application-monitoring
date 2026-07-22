package com.medha.applicationmonitoring.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medha.applicationmonitoring.domain.OrderStatus;
import com.medha.applicationmonitoring.dto.OrderRequest;
import com.medha.applicationmonitoring.dto.OrderResponse;
import com.medha.applicationmonitoring.dto.OrderStatusUpdateRequest;
import com.medha.applicationmonitoring.dto.PageResponse;
import com.medha.applicationmonitoring.exception.InvalidOrderStateTransitionException;
import com.medha.applicationmonitoring.exception.OrderNotFoundException;
import com.medha.applicationmonitoring.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService orderService;

    private OrderResponse sampleResponse() {
        Instant now = Instant.now();
        return new OrderResponse(1L, "Alice", "Widget", 2, BigDecimal.TEN, BigDecimal.valueOf(20),
                OrderStatus.PENDING, now, now);
    }

    @Test
    void createOrder_returns201() throws Exception {
        when(orderService.createOrder(any())).thenReturn(sampleResponse());

        OrderRequest request = new OrderRequest("Alice", "Widget", 2, BigDecimal.TEN);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void createOrder_missingFields_returns400() throws Exception {
        String invalidJson = "{\"customerName\":\"\",\"product\":\"\",\"quantity\":0,\"unitPrice\":-1}";

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    void getOrder_whenNotFound_returns404() throws Exception {
        when(orderService.getOrder(99L)).thenThrow(new OrderNotFoundException(99L));

        mockMvc.perform(get("/api/orders/{id}", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void listOrders_returnsPagedResponse() throws Exception {
        PageResponse<OrderResponse> page = new PageResponse<>(java.util.List.of(sampleResponse()), 0, 20, 1, 1, true);
        when(orderService.listOrders(isNull(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void updateStatus_invalidTransition_returns409() throws Exception {
        when(orderService.updateStatus(eq(1L), any(OrderStatusUpdateRequest.class)))
                .thenThrow(new InvalidOrderStateTransitionException(OrderStatus.COMPLETED, OrderStatus.PROCESSING));

        mockMvc.perform(patch("/api/orders/{id}/status", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PROCESSING\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void cancelOrder_returnsOk() throws Exception {
        when(orderService.cancelOrder(anyLong())).thenReturn(sampleResponse());

        mockMvc.perform(delete("/api/orders/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }
}
