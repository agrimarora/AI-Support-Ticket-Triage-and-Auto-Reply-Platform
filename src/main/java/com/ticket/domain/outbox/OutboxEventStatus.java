package com.ticket.domain.outbox;

public enum OutboxEventStatus {
    PENDING,
    PROCESSED,
    FAILED
}
