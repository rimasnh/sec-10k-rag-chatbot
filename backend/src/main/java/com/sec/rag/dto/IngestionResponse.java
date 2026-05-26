package com.sec.rag.dto;

public record IngestionResponse(
        boolean success,
        int filesProcessed,
        int recordsIndexed,
        String message,
        IngestionTelemetry telemetry
) {
}
