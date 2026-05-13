package com.sec.rag.rag;

import java.util.Map;

public record QdrantPoint(
        String id,
        Map<String, Object> payload,
        Double score
) {
}
