package com.sec.rag.service;

import com.sec.rag.config.AppProperties;
import com.sec.rag.dto.ChatRequest;
import com.sec.rag.dto.ChatResponse;
import com.sec.rag.dto.SourceSnippet;
import com.sec.rag.rag.QdrantPoint;
import com.sec.rag.rag.QdrantService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final String EMPTY_RESPONSE = "No relevant information found for the specified company and year.";
    private static final Pattern SENTENCE_SPLIT_PATTERN = Pattern.compile("(?<=[.!?])\\s+");
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("\\d");
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "did", "do", "does", "for", "from", "had", "has",
            "have", "how", "in", "is", "it", "its", "many", "much", "of", "on", "or", "that", "the", "their",
            "there", "this", "through", "to", "was", "were", "what", "when", "where", "which", "who", "why",
            "with");

    private final AppProperties properties;
    private final QdrantService qdrantService;
    private final Sec10KAssistant assistant;

    public ChatService(AppProperties properties, QdrantService qdrantService, Sec10KAssistant assistant) {
        this.properties = properties;
        this.qdrantService = qdrantService;
        this.assistant = assistant;
    }

    public ChatResponse chat(ChatRequest request) {
        long startNanos = System.nanoTime();
        int topK = request.topK() == null ? properties.getRag().getDefaultTopK() : request.topK();
        QuestionMode questionMode = detectQuestionMode(request.question());
        log.info("Starting chat pipeline company='{}' year={} topK={} questionLength={}",
                request.company(), request.year(), topK, request.question().length());
        List<QdrantPoint> points = qdrantService.search(request.question(), request.company(), request.year(), topK);

        if (points.isEmpty()) {
            log.warn("No vector matches found company='{}' year={} durationMs={}",
                    request.company(), request.year(), elapsedMillis(startNanos));
            return new ChatResponse(EMPTY_RESPONSE, List.of());
        }

        List<QdrantPoint> rerankedPoints = rerankPoints(request.question(), points);
        List<QdrantPoint> promptPoints = preparePromptPoints(request.question(), questionMode, rerankedPoints);

        String context = promptPoints.stream()
                .map(this::formatContextBlock)
                .collect(Collectors.joining("\n\n"));

        String llmPrompt = """
                Use only the context below to answer the user's question.
                If the answer is not fully supported by the context, return exactly:
                No relevant information found for the specified company and year.
                Keep the answer concise, specific, and grounded in the strongest evidence only.
                Prefer 2-4 short bullet points.
                Do not exceed 120 words unless the question explicitly asks for detail.
                %s

                User question:
                %s

                Company filter:
                %s

                Year filter:
                %s

                Context:
                %s
                """.formatted(
                instructionsFor(questionMode),
                request.question(),
                nullToNotSpecified(request.company()),
                request.year() == null ? "Not specified" : request.year(),
                context
        );
        log.info("Sending prompt to LLM mode={} company='{}' year={} promptChars={} retrievedChunks={} promptChunks={}",
                questionMode, request.company(), request.year(), llmPrompt.length(), points.size(), promptPoints.size());
        if (properties.getDebug().isLogLlmPrompts()) {
            log.info("LLM prompt body:\n{}", llmPrompt);
        }

        String answer = assistant.chat(llmPrompt);
        log.info("LLM answer generated company='{}' year={} retrievedChunks={} contextChars={} answerChars={} durationMs={}",
                request.company(),
                request.year(),
                promptPoints.size(),
                context.length(),
                answer == null ? 0 : answer.length(),
                elapsedMillis(startNanos));

        List<SourceSnippet> snippets = rerankedPoints.stream()
                .map(this::toSourceSnippet)
                .toList();

        return new ChatResponse(answer == null || answer.isBlank() ? EMPTY_RESPONSE : answer, snippets);
    }

    private String formatContextBlock(QdrantPoint point) {
        return """
                [Company: %s | Year: %s | Section: %s | Filing Date: %s | Source File: %s]
                %s
                """.formatted(
                value(point, "company"),
                value(point, "year"),
                value(point, "section"),
                value(point, "filing_date"),
                value(point, "source_file"),
                value(point, "text")
        );
    }

    private SourceSnippet toSourceSnippet(QdrantPoint point) {
        return new SourceSnippet(
                point.id(),
                stringValue(point.payload().get("company")),
                integerValue(point.payload().get("year")),
                stringValue(point.payload().get("section")),
                stringValue(point.payload().get("filing_date")),
                stringValue(point.payload().get("source_file")),
                stringValue(point.payload().get("text")),
                point.score()
        );
    }

    private String value(QdrantPoint point, String key) {
        Object value = point.payload().get(key);
        return value == null ? "N/A" : String.valueOf(value);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String nullToNotSpecified(String value) {
        return value == null || value.isBlank() ? "Not specified" : value;
    }

    private List<QdrantPoint> rerankPoints(String question, List<QdrantPoint> points) {
        List<String> questionTokens = tokenize(question);

        return points.stream()
                .sorted(Comparator.comparingDouble((QdrantPoint point) -> combinedScore(questionTokens, point)).reversed())
                .toList();
    }

    private double combinedScore(List<String> questionTokens, QdrantPoint point) {
        String text = stringValue(point.payload().get("text"));
        double vectorScore = point.score() == null ? 0.0 : point.score();
        double lexicalScore = overlapScore(questionTokens, tokenize(text));
        double numericBonus = containsNumericIntent(questionTokens) && text != null && NUMERIC_PATTERN.matcher(text).find()
                ? 0.15
                : 0.0;
        return vectorScore + lexicalScore + numericBonus;
    }

    private List<QdrantPoint> preparePromptPoints(String question, QuestionMode mode, List<QdrantPoint> points) {
        int limit = promptChunkLimit(mode, points.size());
        List<QdrantPoint> limitedPoints = points.stream()
                .limit(limit)
                .toList();

        if (mode == QuestionMode.EXTRACTION || mode == QuestionMode.VERIFICATION) {
            return limitedPoints.stream()
                    .map(point -> narrowPointToRelevantWindow(question, point))
                    .toList();
        }

        return limitedPoints;
    }

    private int promptChunkLimit(QuestionMode mode, int availablePoints) {
        int defaultLimit = Math.min(properties.getRag().getMaxContextChunks(), availablePoints);
        return switch (mode) {
            case EXTRACTION -> Math.min(1, availablePoints);
            case VERIFICATION -> Math.min(2, availablePoints);
            case COMPARISON -> Math.min(4, defaultLimit);
            case SUMMARY -> Math.min(4, defaultLimit);
            case GENERAL -> Math.min(3, defaultLimit);
        };
    }

    private QdrantPoint narrowPointToRelevantWindow(String question, QdrantPoint point) {
        String text = stringValue(point.payload().get("text"));
        if (text == null || text.isBlank()) {
            return point;
        }

        List<String> sentences = List.of(SENTENCE_SPLIT_PATTERN.split(text.trim()));
        if (sentences.size() <= 2) {
            return point;
        }

        List<String> questionTokens = tokenize(question);
        int bestIndex = 0;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int index = 0; index < sentences.size(); index++) {
            String sentence = sentences.get(index);
            double score = overlapScore(questionTokens, tokenize(sentence));
            if (containsNumericIntent(questionTokens) && NUMERIC_PATTERN.matcher(sentence).find()) {
                score += 0.2;
            }
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
            }
        }

        int start = Math.max(0, bestIndex - 1);
        int end = Math.min(sentences.size(), bestIndex + 2);
        String narrowedText = String.join(" ", sentences.subList(start, end)).trim();

        Map<String, Object> payload = new HashMap<>(point.payload());
        payload.put("text", narrowedText);
        return new QdrantPoint(point.id(), payload, point.score());
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        for (String part : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!part.isBlank() && !STOP_WORDS.contains(part)) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private double overlapScore(List<String> leftTokens, List<String> rightTokens) {
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0;
        }

        Set<String> rightSet = new HashSet<>(rightTokens);
        long overlap = leftTokens.stream()
                .filter(rightSet::contains)
                .distinct()
                .count();
        return (double) overlap / Math.max(1, leftTokens.size());
    }

    private boolean containsNumericIntent(List<String> questionTokens) {
        Set<String> tokens = new HashSet<>(questionTokens);
        return tokens.contains("many")
                || tokens.contains("much")
                || tokens.contains("percentage")
                || tokens.contains("percent")
                || tokens.contains("amount")
                || tokens.contains("exact")
                || tokens.contains("count")
                || tokens.contains("number")
                || tokens.contains("users")
                || tokens.contains("revenue");
    }

    private QuestionMode detectQuestionMode(String question) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT).trim();

        if (containsAny(normalized, "how many", "how much", "what percentage", "what percent",
                "what amount", "what exact", "how long", "how large", "how big")) {
            return QuestionMode.EXTRACTION;
        }
        if (containsAny(normalized, "compare", "difference", "different", "changed", "change",
                "versus", "vs", "increase", "decrease", "grew", "declined")) {
            return QuestionMode.COMPARISON;
        }
        if (containsAny(normalized, "does", "did", "is there", "are there", "was there",
                "were there", "has", "have")) {
            return QuestionMode.VERIFICATION;
        }
        if (containsAny(normalized, "summarize", "summary", "overview", "what is discussed",
                "what does this section discuss", "what does item", "describe")) {
            return QuestionMode.SUMMARY;
        }

        return QuestionMode.GENERAL;
    }

    private String instructionsFor(QuestionMode mode) {
        return switch (mode) {
            case EXTRACTION -> """
                    This is an extraction question.
                    If the context contains a count, amount, percentage, date, or other exact fact that answers the question, state the exact value immediately in the first bullet or first sentence.
                    Quote the figure exactly as it appears when possible.
                    Do not replace an exact figure with a generic description.
                    If the exact figure is not present, say that the context does not provide a precise value.
                    """;
            case COMPARISON -> """
                    This is a comparison question.
                    Focus on the differences, changes, increases, decreases, or contrasts supported by the context.
                    Present the comparison directly, and include exact figures when available.
                    Do not provide a broad summary if the question asks for change or contrast.
                    """;
            case VERIFICATION -> """
                    This is a verification question.
                    Start with a direct yes, no, or qualified answer if the context supports one.
                    Then give 1-2 short supporting facts from the context.
                    If the context is ambiguous, say that clearly instead of guessing.
                    """;
            case SUMMARY -> """
                    This is a summary question.
                    Provide a compact synthesis of the key points only.
                    Prefer short bullets over dense prose, and do not include isolated details unless they are central.
                    """;
            case GENERAL -> """
                    If the question asks for a count, amount, percentage, date, or other exact fact and that fact appears in the context, state the exact value immediately.
                    Do not replace an exact figure with a generic description.
                    """;
        };
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private enum QuestionMode {
        EXTRACTION,
        COMPARISON,
        VERIFICATION,
        SUMMARY,
        GENERAL
    }
}
