package com.sec.rag.dto;

public record ChatTelemetry(
        long requestLatencyMs,
        long retrievalLatencyMs,
        long llmLatencyMs
) {
}
