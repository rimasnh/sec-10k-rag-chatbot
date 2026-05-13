package com.sec.rag.dto;

public record SourceSnippet(
        String chunkId,
        String company,
        Integer year,
        String section,
        String filingDate,
        String sourceFile,
        String text,
        Double relevance
) {
}
