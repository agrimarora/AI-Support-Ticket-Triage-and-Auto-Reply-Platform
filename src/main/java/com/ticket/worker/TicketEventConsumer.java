package com.ticket.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticket.domain.consumer.ProcessedEvent;
import com.ticket.domain.consumer.ProcessedEventRepository;
import com.ticket.domain.ticket.Ticket;
import com.ticket.domain.ticket.TicketRepository;
import com.ticket.domain.ticket.TicketStatus;
import com.ticket.dto.ClassificationResultDto;
import com.ticket.dto.TicketEventDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class TicketEventConsumer {

    public static final String CONSUMER_GROUP = "ticket-triage-group";

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final TicketRepository ticketRepository;
    private final ClassificationWorker classificationWorker;

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltTopicSuffix = "-dlt"
    )
    @KafkaListener(topics = "${ticket.kafka.topics.ticket-events:ticket-events}", groupId = CONSUMER_GROUP)
    @Transactional
    public void processTicketEvent(String messagePayload, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) throws Exception {
        log.info("Received event on topic [{}]: {}", topic, messagePayload);

        TicketEventDto event = objectMapper.readValue(messagePayload, TicketEventDto.class);

        if (event.getEventId() != null && processedEventRepository.existsByEventIdAndConsumerGroup(event.getEventId(), CONSUMER_GROUP)) {
            log.info("Event ID [{}] already processed by consumer group [{}]. Skipping for idempotency.",
                    event.getEventId(), CONSUMER_GROUP);
            return;
        }

        if ("POISON_PILL".equalsIgnoreCase(event.getTitle())) {
            throw new RuntimeException("Simulated processing failure for testing retry & DLT routing");
        }

        Optional<Ticket> ticketOpt = ticketRepository.findById(event.getTicketId());
        if (ticketOpt.isEmpty()) {
            log.warn("Ticket ID [{}] not found in database, skipping processing.", event.getTicketId());
            return;
        }

        Ticket ticket = ticketOpt.get();
        ticket.setStatus(TicketStatus.CLASSIFYING);
        ticketRepository.save(ticket);

        ClassificationResultDto classification = classificationWorker.classifyTicket(event);

        ticket.setCategory(classification.getCategory());
        ticket.setPriority(classification.getPriority());
        ticket.setConfidenceScore(classification.getConfidenceScore());
        ticket.setStatus(TicketStatus.CLASSIFIED);
        ticketRepository.save(ticket);

        if (event.getEventId() != null) {
            ProcessedEvent processedEvent = ProcessedEvent.builder()
                    .eventId(event.getEventId())
                    .consumerGroup(CONSUMER_GROUP)
                    .processedAt(OffsetDateTime.now())
                    .build();
            processedEventRepository.save(processedEvent);
        }

        log.info("Successfully classified ticket ID [{}] as category: {}, priority: {}",
                ticket.getId(), ticket.getCategory(), ticket.getPriority());
    }

    @DltHandler
    public void handleDeadLetterTopic(String messagePayload, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.error("CRITICAL: Message landed in Dead-Letter Topic [{}]: {}", topic, messagePayload);
    }
}
