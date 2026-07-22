package com.medha.applicationmonitoring.exception;

import com.medha.applicationmonitoring.domain.OrderStatus;

public class InvalidOrderStateTransitionException extends RuntimeException {

    public InvalidOrderStateTransitionException(OrderStatus from, OrderStatus to) {
        super("Cannot transition order from " + from + " to " + to);
    }
}
