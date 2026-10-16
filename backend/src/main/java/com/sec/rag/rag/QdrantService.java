package com.sec.rag.rag;

import com.sec.rag.config.AppProperties;
import com.sec.rag.ingestion.ChunkedSection;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Service
public class QdrantService {

    private static final Logger log = LoggerFactory.getLogger(QdrantService.class);
    private static final int UPSERT_BATCH_SIZE = 100;

    private final AppProperties properties;
    private final WebClient qdrantWebClient;
    private final EmbeddingModel embeddingModel;

    public QdrantService(AppProperties properties, WebClient qdrantWebClient, EmbeddingModel embeddingModel) {
        this.properties = properties;
        this.qdrantWebClient = qdrantWebClient;
        this.embeddingModel = embeddingModel;
    }

    public void ensureCollectionExists() {
        String collection = properties.getQdrant().getCollection();
        log.debug("Ensuring Qdrant collection exists name='{}' url='{}'", collection, properties.getQdrant().getUrl());
        try {
            qdrantWebClient.get().uri("/collections/{collection}", collection).retrieve().toBodilessEntity().block();
        } catch (WebClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                createCollection(collection);
                return;
            }
            throw exception;
        }
    }

    public void upsert(List<ChunkedSection> chunks) {
        if (chunks.isEmpty()) {
            log.debug("Skipping Qdrant upsert because no chunks were provided");
            return;
        }
        // Local testing only: uncomment to embed and store just the first 5 chunks
        // per upsert call. Keep commented out when ingesting the full dataset.
        // chunks = chunks.stream().limit(5).toList();

        long startNanos = System.nanoTime();
        int uploaded = 0;
        List<Map<String, Object>> points = new ArrayList<>(UPSERT_BATCH_SIZE);
        log.info("Starting embedding and upload chunks={} batchSize={} collection='{}'", chunks.size(),
                UPSERT_BATCH_SIZE, properties.getQdrant().getCollection());
        for (ChunkedSection chunk : chunks) {
            Embedding embedding = embeddingModel.embed(chunk.text()).content();
            Map<String, Object> payload = new HashMap<>();
            payload.put("company", chunk.company());
            payload.put("year", chunk.year());
            payload.put("section", chunk.section());
            payload.put("filing_date", chunk.filingDate());
            payload.put("source_file", chunk.sourceFile());
            payload.put("text", chunk.text());

            points.add(Map.of("id", chunk.id(), "vector", embedding.vectorAsList(), "payload", payload));
            if (points.size() == UPSERT_BATCH_SIZE) {
                uploadBatch(points);
                uploaded += points.size();
                points.clear();
                log.info("Ingestion progress uploaded={}/{} collection='{}' durationMs={}", uploaded, chunks.size(),
                        properties.getQdrant().getCollection(), elapsedMillis(startNanos));
            }
        }
        if (!points.isEmpty()) {
            uploadBatch(points);
            uploaded += points.size();
            log.info("Ingestion progress uploaded={}/{} collection='{}' durationMs={}", uploaded, chunks.size(),
                    properties.getQdrant().getCollection(), elapsedMillis(startNanos));
        }
        log.info("Upserted {} vectors into collection='{}' durationMs={}", uploaded,
                properties.getQdrant().getCollection(), elapsedMillis(startNanos));
    }

    private void uploadBatch(List<Map<String, Object>> points) {
        qdrantWebClient.put().uri("/collections/{collection}/points?wait=true", properties.getQdrant().getCollection())
                .bodyValue(Map.of("points", points)).retrieve().toBodilessEntity().timeout(Duration.ofSeconds(30))
                .block();
    }

    public List<QdrantPoint> search(String question, String company, Integer year, int limit) {
        long startNanos = System.nanoTime();
        log.debug("Running Qdrant search collection='{}' company='{}' year={} limit={} questionLength={}",
                properties.getQdrant().getCollection(), company, year, limit, question == null ? 0 : question.length());
        Embedding embedding = embeddingModel.embed(question).content();

        Map<String, Object> body = new HashMap<>();
        body.put("vector", embedding.vectorAsList());
        body.put("limit", limit);
        body.put("with_payload", true);

        List<Map<String, Object>> must = new ArrayList<>();
        if (company != null && !company.isBlank()) {
            must.add(matchFilter("company", company));
        }
        if (year != null) {
            must.add(matchFilter("year", year));
        }
        if (!must.isEmpty()) {
            body.put("filter", Map.of("must", must));
        }

        Map<String, Object> response = qdrantWebClient.post()
                .uri("/collections/{collection}/points/search", properties.getQdrant().getCollection()).bodyValue(body)
                .retrieve().bodyToMono(Map.class).block();

        List<Map<String, Object>> result = response == null ? List.of()
                : (List<Map<String, Object>>) response.getOrDefault("result", List.of());

        List<QdrantPoint> points = new ArrayList<>();
        for (Map<String, Object> item : result) {
            Map<String, Object> payload = (Map<String, Object>) item.getOrDefault("payload", Map.of());
            Double score = item.get("score") instanceof Number number ? number.doubleValue() : null;
            points.add(new QdrantPoint(String.valueOf(item.get("id")), payload, score));
        }
        log.info("Qdrant search completed results={} company='{}' year={} durationMs={}", points.size(), company, year,
                elapsedMillis(startNanos));
        return points;
    }

    private void createCollection(String collection) {
        Map<String, Object> body = Map.of("vectors",
                Map.of("size", properties.getQdrant().getDimension(), "distance", "Cosine"));

        qdrantWebClient.put().uri("/collections/{collection}", collection).bodyValue(body).retrieve().toBodilessEntity()
                .block();
        log.info("Created Qdrant collection {}", collection);
    }

    private Map<String, Object> matchFilter(String key, Object value) {
        return Map.of("key", key, "match", Map.of("value", value));
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
