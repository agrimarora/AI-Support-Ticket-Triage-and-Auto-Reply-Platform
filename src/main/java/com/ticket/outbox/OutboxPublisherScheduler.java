package com.ticket.outbox;

import com.ticket.domain.outbox.OutboxEvent;
import com.ticket.domain.outbox.OutboxEventRepository;
import com.ticket.domain.outbox.OutboxEventStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisherScheduler {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${ticket.kafka.topics.ticket-events:ticket-events}")
    private String ticketEventsTopic;

    @Scheduled(fixedDelayString = "${ticket.outbox.publisher-fixed-delay-ms:2000}")
    @Transactional
    public void publishPendingOutboxEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findByStatusOrderByCreatedAtAsc(
                OutboxEventStatus.PENDING, PageRequest.of(0, 50));

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("Found {} pending outbox events to publish to Kafka", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                kafkaTemplate.send(ticketEventsTopic, event.getTenantId().toString(), event.getPayload())
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                log.error("Failed to publish outbox event id: {} to topic: {}", event.getId(), ticketEventsTopic, ex);
                            } else {
                                log.debug("Successfully published outbox event id: {} to offset: {}",
                                        event.getId(), result.getRecordMetadata().offset());
                            }
                        });

                event.setStatus(OutboxEventStatus.PROCESSED);
                event.setProcessedAt(OffsetDateTime.now());
                outboxEventRepository.save(event);
            } catch (Exception e) {
                log.error("Exception occurred while dispatching outbox event id: {}", event.getId(), e);
                event.setStatus(OutboxEventStatus.FAILED);
                outboxEventRepository.save(event);
            }
        }
    }
}
