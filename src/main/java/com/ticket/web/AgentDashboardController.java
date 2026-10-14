package com.ticket.web;

import com.ticket.domain.ticket.Ticket;
import com.ticket.domain.ticket.TicketRepository;
import com.ticket.domain.ticket.TicketStatus;
import com.ticket.dto.TicketResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@RestController
@RequestMapping("/v1/dashboard")
@RequiredArgsConstructor
public class AgentDashboardController {

    private final TicketRepository ticketRepository;
    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> tenantEmitters = new ConcurrentHashMap<>();

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeToTicketEvents(@RequestParam("tenantId") UUID tenantId) {
        SseEmitter emitter = new SseEmitter(180_000L); // 3 minutes timeout

        tenantEmitters.computeIfAbsent(tenantId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(tenantId, emitter));
        emitter.onTimeout(() -> removeEmitter(tenantId, emitter));
        emitter.onError((e) -> removeEmitter(tenantId, emitter));

        try {
            emitter.send(SseEmitter.event().name("INIT").data("Connected to agent live updates for tenant: " + tenantId));
        } catch (IOException e) {
            removeEmitter(tenantId, emitter);
        }

        return emitter;
    }

    public void broadcastTicketUpdate(UUID tenantId, Ticket ticket) {
        CopyOnWriteArrayList<SseEmitter> emitters = tenantEmitters.get(tenantId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        TicketResponse payload = TicketResponse.fromEntity(ticket);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("TICKET_UPDATED")
                        .data(payload));
            } catch (Exception e) {
                removeEmitter(tenantId, emitter);
            }
        }
    }

    @PostMapping("/tickets/{ticketId}/approve")
    public ResponseEntity<TicketResponse> approveDraft(
            @PathVariable("ticketId") UUID ticketId,
            @RequestParam(value = "feedback", required = false) String feedback) {

        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketId));

        ticket.setStatus(TicketStatus.APPROVED);
        ticket.setAgentFeedback(feedback != null ? feedback : "Approved without edits");
        Ticket saved = ticketRepository.save(ticket);

        broadcastTicketUpdate(saved.getTenantId(), saved);
        log.info("Agent approved draft for ticket [{}]", ticketId);

        return ResponseEntity.ok(TicketResponse.fromEntity(saved));
    }

    @PostMapping("/tickets/{ticketId}/reject")
    public ResponseEntity<TicketResponse> rejectDraft(
            @PathVariable("ticketId") UUID ticketId,
            @RequestParam("reason") String reason) {

        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketId));

        ticket.setStatus(TicketStatus.REJECTED);
        ticket.setAgentFeedback("Rejected: " + reason);
        ticket.setRequiresHuman(true);
        Ticket saved = ticketRepository.save(ticket);

        broadcastTicketUpdate(saved.getTenantId(), saved);
        log.info("Agent rejected draft for ticket [{}] with reason: {}", ticketId, reason);

        return ResponseEntity.ok(TicketResponse.fromEntity(saved));
    }

    private void removeEmitter(UUID tenantId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = tenantEmitters.get(tenantId);
        if (emitters != null) {
            emitters.remove(emitter);
        }
    }
}
