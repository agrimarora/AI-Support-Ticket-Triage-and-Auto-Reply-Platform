package com.ticket.domain.ticket;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    Optional<Ticket> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    Page<Ticket> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<Ticket> findByIdAndTenantId(UUID id, UUID tenantId);
}
