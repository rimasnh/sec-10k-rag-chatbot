package com.sec.rag.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sec.rag.config.AppProperties;
import com.sec.rag.ingestion.ChunkedSection;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QdrantServiceTests {

    @ParameterizedTest
    @CsvSource({
            "What does Item 7A say about risk?, 7A",
            "Summarize item 7a., 7A",
            "Explain section 7 A., 7A",
            "Summarize Item 7., 7",
            "Compare Item 7 and Item 7A., BOTH",
            "Compare items 7 and 7A., BOTH",
            "What are the market risks?, BOTH"
    })
    void searchesExplicitSectionWhilePreservingCompanyYearAndLimit(String question, String section) throws Exception {
        AtomicReference<String> sentBody = new AtomicReference<>();
        EmbeddingModel model = segments -> Response.from(segments.stream()
                .map(segment -> Embedding.from(new float[] {1, 0})).toList());
        var client = WebClient.builder().exchangeFunction(request -> {
            assertEquals("/collections/sec_10k_sections/points/search", request.url().getPath());
            var output = new MockClientHttpRequest(request.method(), request.url());
            return request.writeTo(output, ExchangeStrategies.withDefaults())
                    .then(Mono.defer(output::getBodyAsString))
                    .map(json -> {
                        sentBody.set(json);
                        return ClientResponse.create(HttpStatus.OK)
                                .header("Content-Type", "application/json")
                                .body("{\"result\":[{\"id\":\"match\",\"score\":0.9,\"payload\":{\"section\":\"7A\"}}]}")
                                .build();
                    });
        }).build();

        var properties = new AppProperties();
        properties.getQdrant().setCollection("sec_10k_sections");
        var points = new QdrantService(properties, client, model)
                .search(question, "Company", 2022, 5);

        var body = new ObjectMapper().readTree(sentBody.get());
        assertEquals(5, body.get("limit").asInt());
        assertEquals(1.0, body.get("vector").get(0).asDouble());
        var filters = body.get("filter").get("must");
        assertEquals("Company", filters.get(0).get("match").get("value").asText());
        assertEquals(2022, filters.get(1).get("match").get("value").asInt());
        assertEquals(section.equals("BOTH") ? 2 : 3, filters.size());
        if (!section.equals("BOTH")) {
            assertEquals("section", filters.get(2).get("key").asText());
            assertEquals(section, filters.get(2).get("match").get("value").asText());
        }
        assertEquals("match", points.getFirst().id());
        assertEquals(0.9, points.getFirst().score());
    }

    @Test
    void uploadsBoundedBatchesBeforeEmbeddingTheWholeFile() throws Exception {
        AtomicInteger embedded = new AtomicInteger();
        List<Integer> batchSizes = new ArrayList<>();
        List<Integer> embeddedAtUpload = new ArrayList<>();
        List<String> uploadedIds = new ArrayList<>();
        EmbeddingModel model = segments -> {
            embedded.addAndGet(segments.size());
            return Response.from(segments.stream()
                    .map(segment -> Embedding.from(new float[] {1, 0})).toList());
        };
        var client = WebClient.builder().exchangeFunction(request -> {
            var output = new MockClientHttpRequest(request.method(), request.url());
            return request.writeTo(output, ExchangeStrategies.withDefaults())
                    .then(Mono.defer(output::getBodyAsString))
                    .flatMap(json -> {
                        try {
                            var body = new ObjectMapper().readTree(json);
                            batchSizes.add(body.get("points").size());
                            embeddedAtUpload.add(embedded.get());
                            body.get("points").forEach(point -> uploadedIds.add(point.get("id").asText()));
                            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                        } catch (Exception exception) {
                            return Mono.error(exception);
                        }
                    });
        }).build();
        var service = new QdrantService(new AppProperties(), client, model);
        var chunks = IntStream.range(0, 205)
                .mapToObj(i -> new ChunkedSection(Integer.toString(i), "Filing text", "Company", 2022,
                        "7", "2022-01-01", "2022.parquet"))
                .toList();

        service.upsert(chunks);

        assertEquals(List.of(100, 100, 5), batchSizes);
        assertEquals(List.of(100, 200, 205), embeddedAtUpload);
        assertEquals(chunks.stream().map(ChunkedSection::id).toList(), uploadedIds);

        service.upsert(List.of());
        assertEquals(3, batchSizes.size());
        assertEquals(205, embedded.get());
    }
}
