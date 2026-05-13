package com.sec.rag.ingestion;

public record ChunkedSection(
        String id,
        String text,
        String company,
        Integer year,
        String section,
        String filingDate,
        String sourceFile
) {
}
