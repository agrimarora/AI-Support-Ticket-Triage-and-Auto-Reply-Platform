package com.ticket.domain.budget;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TenantBudgetRepository extends JpaRepository<TenantBudget, UUID> {
}
