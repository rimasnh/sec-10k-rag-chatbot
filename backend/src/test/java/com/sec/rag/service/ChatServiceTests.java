package com.sec.rag.service;

import com.sec.rag.config.AppProperties;
import com.sec.rag.dto.ChatRequest;
import com.sec.rag.rag.QdrantPoint;
import com.sec.rag.rag.QdrantService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatServiceTests {

    @Test
    void retriesRejectedCombinedContextWithShortExtractionSystemAndOnePassage() {
        var properties = new AppProperties();
        String missing = "No relevant information found for the specified company and year.";
        String expected = "NortonLifeLock empowers nearly 80 million users.";
        String evidence = "NortonLifeLock empowers nearly 80 million users through its Cyber Safety platform.";
        var candidates = List.of(point("answer", evidence, 1.0),
                point("other", "Operating costs increased by $46 million.", 0.1));
        var search = new QdrantService(properties, null, null) {
            @Override
            public List<QdrantPoint> search(String q, String company, Integer year, int limit) {
                return candidates;
            }
        };
        var requests = new ArrayList<dev.langchain4j.model.chat.request.ChatRequest>();
        ChatModel model = new ChatModel() {
            @Override
            public dev.langchain4j.model.chat.response.ChatResponse doChat(
                    dev.langchain4j.model.chat.request.ChatRequest request) {
                requests.add(request);
                return dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(AiMessage.from(requests.size() == 1 ? missing : expected)).build();
            }
        };
        var extractor = AiServices.builder(Sec10KAssistant.class).chatModel(model).build();

        var response = new ChatService(properties, search, extractor)
                .chat(new ChatRequest("How many users does NortonLifeLock empower through its Cyber Safety platform?",
                        "NortonLifeLock Inc.", 2022, 5));

        assertEquals(expected, response.answer());
        assertEquals(2, requests.size());
        requests.forEach(request -> {
            String system = ((SystemMessage) request.messages().getFirst()).text();
            assertTrue(system.startsWith("Extract the requested fact"));
            assertFalse(system.contains("financial analyst"));
        });
        String retry = ((dev.langchain4j.data.message.UserMessage) requests.getLast().messages().getLast()).singleText();
        assertTrue(retry.contains(evidence));
        assertFalse(retry.contains("$46 million"));
        assertFalse(retry.contains("Filing Date"));
    }

    @Test
    void stopsAfterCheckingAvailablePassagesWhenNoAnswerIsSupported() {
        var properties = new AppProperties();
        String missing = "No relevant information found for the specified company and year.";
        var candidates = List.of(point("other", "Operating costs increased by $46 million.", 0.1));
        var search = new QdrantService(properties, null, null) {
            @Override
            public List<QdrantPoint> search(String q, String company, Integer year, int limit) {
                return candidates;
            }
        };
        var calls = new AtomicInteger();
        var response = new ChatService(properties, search, assistant(message -> {
            calls.incrementAndGet();
            return missing;
        })).chat(new ChatRequest("How many users are there?", "Company", 2022, 5));

        assertEquals(missing, response.answer());
        assertEquals(2, calls.get());
    }

    @ParameterizedTest
    @CsvSource({
            "How much did NortonLifeLock borrow under the First Amendment of its Initial Term Loan?, We borrowed $525 million under the First Amendment of our Initial Term Loan.",
            "How many users does NortonLifeLock empower through its Cyber Safety platform?, NortonLifeLock empowers nearly 80 million users through its Cyber Safety platform."
    })
    void numericQuestionsSearchBeyondFiveAndKeepAnswerEvidence(String question, String evidence) {
        var properties = new AppProperties();
        AtomicInteger requestedLimit = new AtomicInteger();
        AtomicReference<String> prompt = new AtomicReference<>();
        var unrelated = IntStream.range(0, 5)
                .mapToObj(i -> point("other-" + i, "An undrawn revolving credit facility of $1 billion.", 0.95))
                .toList();
        var candidates = new java.util.ArrayList<>(unrelated);
        // Simulate the answer falling outside the original top-five vector results.
        candidates.add(point("answer", evidence, 0.9));
        var search = new QdrantService(properties, null, null) {
            @Override
            public List<QdrantPoint> search(String q, String company, Integer year, int limit) {
                requestedLimit.set(limit);
                return candidates;
            }
        };
        var service = new ChatService(properties, search, assistant(text -> {
            prompt.set(text);
            return "Grounded answer";
        }));

        var response = service.chat(new ChatRequest(question, "NortonLifeLock Inc.", 2022, 5));

        assertEquals(20, requestedLimit.get());
        assertTrue(prompt.get().contains(evidence), "The answer passage must reach the model");
        assertEquals(5, response.sources().size());
        assertTrue(response.sources().stream().anyMatch(source -> source.chunkId().equals("answer")));
        response.sources().forEach(source -> assertTrue(prompt.get().contains(source.text())));
    }

    @ParameterizedTest
    @CsvSource({"1, 1", "3, 3", "6, 5"})
    void numericContextHonorsConfiguredCapAndDisplaysOnlySuppliedEvidence(int cap, int expectedCount) {
        var properties = new AppProperties();
        properties.getRag().setMaxContextChunks(cap);
        AtomicReference<String> prompt = new AtomicReference<>();
        var candidates = IntStream.range(0, 5)
                .mapToObj(i -> point("point-" + i, "Unique evidence " + i + ".", 1.0 - i * 0.1))
                .toList();
        var search = new QdrantService(properties, null, null) {
            @Override
            public List<QdrantPoint> search(String q, String company, Integer year, int limit) {
                return candidates;
            }
        };
        var response = new ChatService(properties, search, assistant(text -> {
            prompt.set(text);
            return "Answer";
        })).chat(new ChatRequest("How much was borrowed?", "Company", 2022, 5));

        assertEquals(expectedCount, response.sources().size());
        for (int i = 0; i < candidates.size(); i++) {
            if (i < expectedCount) {
                assertTrue(prompt.get().contains(candidates.get(i).payload().get("text").toString()));
            } else {
                assertFalse(prompt.get().contains(candidates.get(i).payload().get("text").toString()));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "How many users does NortonLifeLock empower through its Cyber Safety platform?, 'NortonLifeLock Inc. has the largest consumer Cyber Safety platform in the world, empowering nearly 80 million users in more than 150 countries.'",
            "How much did NortonLifeLock borrow under the First Amendment of its Initial Term Loan?, We borrowed $525 million under the First Amendment of our Initial Term Loan."
    })
    void numericQuestionsCompactLongChunksWhilePreservingTheAnswer(String question, String evidence) {
        var properties = new AppProperties();
        AtomicReference<String> prompt = new AtomicReference<>();
        String filler = "Unrelated operating expenses were $10 million. ";
        String fullText = filler.repeat(60) + evidence + " " + filler.repeat(60);
        var candidates = IntStream.range(0, 5)
                .mapToObj(i -> point("point-" + i, fullText, 1.0 - i * 0.1))
                .toList();
        var search = new QdrantService(properties, null, null) {
            @Override
            public List<QdrantPoint> search(String q, String company, Integer year, int limit) {
                return candidates;
            }
        };

        var response = new ChatService(properties, search, assistant(text -> {
            prompt.set(text);
            return "Answer";
        })).chat(new ChatRequest(question, "NortonLifeLock Inc.", 2022, 5));

        assertEquals(5, response.sources().size());
        assertTrue(prompt.get().contains(evidence));
        assertTrue(prompt.get().length() < fullText.length(),
                "Five compact passages should be shorter than one original long chunk");
        assertTrue(prompt.get().indexOf("Question: " + question) > prompt.get().lastIndexOf(evidence));
        response.sources().forEach(source -> {
            assertTrue(source.text().contains(evidence));
            assertTrue(source.text().length() < fullText.length() / 4);
            assertTrue(prompt.get().contains(source.text()));
        });
    }

    private static Sec10KAssistant assistant(java.util.function.UnaryOperator<String> respond) {
        return new Sec10KAssistant() {
            @Override
            public String chat(String message) {
                return respond.apply(message);
            }

            @Override
            public String extractFact(String message) {
                return respond.apply(message);
            }
        };
    }

    private static QdrantPoint point(String id, String text, double score) {
        return new QdrantPoint(id, Map.of("company", "NortonLifeLock Inc.", "year", 2022,
                "section", "7", "text", text), score);
    }
}
