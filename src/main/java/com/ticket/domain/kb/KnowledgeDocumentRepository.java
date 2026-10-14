package com.ticket.domain.kb;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, UUID> {
    Optional<KnowledgeDocument> findByTenantIdAndContentHash(UUID tenantId, String contentHash);
    List<KnowledgeDocument> findByTenantId(UUID tenantId);
}
