package com.sec.rag.ingestion;

public record FilingRecord(
        String company,
        String cik,
        Integer year,
        String filingDate,
        String section,
        String sectionTitle,
        String text,
        String formType,
        String sourceFile
) {
}
