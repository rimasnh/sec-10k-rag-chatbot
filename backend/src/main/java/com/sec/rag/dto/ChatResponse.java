package com.sec.rag.dto;

import java.util.List;

public record ChatResponse(
        String answer,
        List<SourceSnippet> sources,
        ChatTelemetry telemetry
) {
}
