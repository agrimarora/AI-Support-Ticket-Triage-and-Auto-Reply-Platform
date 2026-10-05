package com.ticket.dto;

import com.ticket.domain.ticket.TicketPriority;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClassificationResultDto {
    private String category;
    private TicketPriority priority;
    private Double confidenceScore;
    private String rationale;
}
