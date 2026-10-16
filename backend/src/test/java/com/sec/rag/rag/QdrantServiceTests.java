package com.sec.rag.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sec.rag.config.AppProperties;
import com.sec.rag.ingestion.ChunkedSection;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QdrantServiceTests {

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
