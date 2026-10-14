package com.ticket.domain.ai;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class GuardrailService {

    // Regex patterns for sensitive PII (Emails, Credit Cards, SSN/Phone numbers)
    private static final Pattern EMAIL_PATTERN = Pattern.compile("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private static final Pattern CARD_PATTERN = Pattern.compile("\\b(?:\\d[ -]*?){13,16}\\b");
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\b(?:\\+?\\d{1,3}[- ]?)?\\(?\\d{3}\\)?[- ]?\\d{3}[- ]?\\d{4}\\b");

    // Prompt injection indicator patterns
    private static final Pattern INJECTION_PATTERN = Pattern.compile(
            "(?i)(ignore (all )?previous instructions|system prompt|jailbreak|disregard guidelines|you are now DAN|output system secret)"
    );

    public String maskPii(String input) {
        if (input == null) return "";
        String sanitized = EMAIL_PATTERN.matcher(input).replaceAll("[REDACTED_EMAIL]");
        sanitized = CARD_PATTERN.matcher(sanitized).replaceAll("[REDACTED_CARD]");
        sanitized = PHONE_PATTERN.matcher(sanitized).replaceAll("[REDACTED_PHONE]");
        return sanitized;
    }

    public boolean detectPromptInjection(String input) {
        if (input == null) return false;
        return INJECTION_PATTERN.matcher(input).find();
    }
}
