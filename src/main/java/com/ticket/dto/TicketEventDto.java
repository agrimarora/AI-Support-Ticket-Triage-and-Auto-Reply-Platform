package com.ticket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketEventDto {
    private String eventId;
    private UUID tenantId;
    private UUID ticketId;
    private String customerId;
    private String title;
    private String description;
    private String eventType;
}
