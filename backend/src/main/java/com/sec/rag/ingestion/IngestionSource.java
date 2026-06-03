package com.sec.rag.ingestion;

public enum IngestionSource {
    LOCAL,
    BLOB;

    public static IngestionSource from(String value) {
        if (value == null || value.isBlank()) {
            return LOCAL;
        }
        return IngestionSource.valueOf(value.trim().toUpperCase());
    }
}
