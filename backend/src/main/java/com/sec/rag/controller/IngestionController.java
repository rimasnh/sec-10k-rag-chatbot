package com.sec.rag.controller;

import com.sec.rag.dto.IngestionResponse;
import com.sec.rag.ingestion.IngestionSource;
import com.sec.rag.ingestion.IngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class IngestionController {

    private static final Logger log = LoggerFactory.getLogger(IngestionController.class);

    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<IngestionResponse> ingest(@RequestParam(defaultValue = "local") String source) {
        IngestionSource ingestionSource = IngestionSource.from(source);
        log.info("Manual ingestion endpoint invoked source={}", ingestionSource);
        return ResponseEntity.ok(ingestionService.ingestAll(ingestionSource));
    }
}
