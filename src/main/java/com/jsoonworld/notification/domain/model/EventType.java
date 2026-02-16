package com.jsoonworld.notification.domain.model;

public enum EventType {
    PAYMENT_APPROVED,
    PAYMENT_FAILED,

    SUBSCRIPTION_RENEWED,
    SUBSCRIPTION_EXPIRING,
    SUBSCRIPTION_CANCELLED,

    ORDER_CREATED,
    ORDER_COMPLETED,
    ORDER_CANCELLED,

    REFUND_COMPLETED,
    REFUND_FAILED;

    /**
     * fluxpay-engine의 eventType 문자열을 enum으로 변환한다.
     * 예: "payment.approved" -> PAYMENT_APPROVED
     */
    public static EventType fromEventTypeString(String eventType) {
        return switch (eventType) {
            case "payment.approved" -> PAYMENT_APPROVED;
            case "payment.failed" -> PAYMENT_FAILED;
            case "subscription.renewed" -> SUBSCRIPTION_RENEWED;
            case "subscription.expiring" -> SUBSCRIPTION_EXPIRING;
            case "subscription.cancelled" -> SUBSCRIPTION_CANCELLED;
            case "order.created" -> ORDER_CREATED;
            case "order.completed" -> ORDER_COMPLETED;
            case "order.cancelled" -> ORDER_CANCELLED;
            case "refund.completed" -> REFUND_COMPLETED;
            case "refund.failed" -> REFUND_FAILED;
            default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
        };
    }
}
