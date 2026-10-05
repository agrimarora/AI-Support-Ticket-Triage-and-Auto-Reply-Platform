package com.ticket.domain.ticket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticket.core.exception.TicketNotFoundException;
import com.ticket.core.tenant.TenantContext;
import com.ticket.domain.outbox.OutboxEvent;
import com.ticket.domain.outbox.OutboxEventRepository;
import com.ticket.domain.outbox.OutboxEventStatus;
import com.ticket.dto.CreateTicketRequest;
import com.ticket.dto.TicketEventDto;
import com.ticket.dto.TicketResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketService {

    private final TicketRepository ticketRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public TicketResponse createTicket(CreateTicketRequest request, String idempotencyKey) {
        UUID tenantId = TenantContext.getRequiredTenantId();

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<Ticket> existingTicket = ticketRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
            if (existingTicket.isPresent()) {
                log.info("Idempotent request match for tenant [{}] and key [{}]", tenantId, idempotencyKey);
                return TicketResponse.fromEntity(existingTicket.get());
            }
        }

        Ticket ticket = Ticket.builder()
                .tenantId(tenantId)
                .customerId(request.getCustomerId())
                .title(request.getTitle())
                .description(request.getDescription())
                .status(TicketStatus.RECEIVED)
                .idempotencyKey(idempotencyKey)
                .build();

        Ticket savedTicket = ticketRepository.save(ticket);

        UUID eventId = UUID.randomUUID();
        TicketEventDto eventDto = TicketEventDto.builder()
                .eventId(eventId.toString())
                .tenantId(tenantId)
                .ticketId(savedTicket.getId())
                .customerId(savedTicket.getCustomerId())
                .title(savedTicket.getTitle())
                .description(savedTicket.getDescription())
                .eventType("TICKET_CREATED")
                .build();

        try {
            String payloadJson = objectMapper.writeValueAsString(eventDto);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .id(eventId)
                    .tenantId(tenantId)
                    .aggregateType("TICKET")
                    .aggregateId(savedTicket.getId().toString())
                    .eventType("TICKET_CREATED")
                    .payload(payloadJson)
                    .status(OutboxEventStatus.PENDING)
                    .build();

            outboxEventRepository.save(outboxEvent);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize ticket outbox event", e);
        }

        log.info("Successfully created ticket ID [{}] for tenant [{}]", savedTicket.getId(), tenantId);
        return TicketResponse.fromEntity(savedTicket);
    }

    @Transactional(readOnly = true)
    public Page<TicketResponse> getTickets(Pageable pageable) {
        UUID tenantId = TenantContext.getRequiredTenantId();
        return ticketRepository.findByTenantId(tenantId, pageable)
                .map(TicketResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public TicketResponse getTicketById(UUID ticketId) {
        UUID tenantId = TenantContext.getRequiredTenantId();
        Ticket ticket = ticketRepository.findByIdAndTenantId(ticketId, tenantId)
                .orElseThrow(() -> new TicketNotFoundException("Ticket not found with id: " + ticketId));
        return TicketResponse.fromEntity(ticket);
    }
}
