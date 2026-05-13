package com.sec.rag.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        @NotBlank String question,
        String company,
        Integer year,
        @Min(1) @Max(10) Integer topK
) {
}
