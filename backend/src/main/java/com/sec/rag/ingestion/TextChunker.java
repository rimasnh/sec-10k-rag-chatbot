package com.sec.rag.ingestion;

import com.sec.rag.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TextChunker {

    private final AppProperties properties;

    public TextChunker(AppProperties properties) {
        this.properties = properties;
    }

    public List<ChunkedSection> chunk(FilingRecord record) {
        List<String> words = new ArrayList<>(List.of(record.text().split("\\s+")));
        int chunkSize = properties.getRag().getChunkSizeTokens();
        int overlap = properties.getRag().getChunkOverlapTokens();
        int step = Math.max(1, chunkSize - overlap);

        List<ChunkedSection> chunks = new ArrayList<>();
        for (int start = 0; start < words.size(); start += step) {
            int end = Math.min(words.size(), start + chunkSize);
            String text = String.join(" ", words.subList(start, end)).trim();
            if (!text.isBlank()) {
                chunks.add(new ChunkedSection(
                        deterministicId(record, start, end),
                        text,
                        record.company(),
                        record.year(),
                        normalizeSection(record.section()),
                        record.filingDate(),
                        record.sourceFile()
                ));
            }

            if (end >= words.size()) {
                break;
            }
        }
        return chunks;
    }

    private String normalizeSection(String section) {
        if (section == null) {
            return null;
        }
        String normalized = section.toUpperCase().replace("ITEM", "").trim();
        if ("7A".equals(normalized)) {
            return "7A";
        }
        return "7";
    }

    private String deterministicId(FilingRecord record, int start, int end) {
        String raw = String.join("|",
                String.valueOf(record.sourceFile()),
                String.valueOf(record.company()),
                String.valueOf(record.year()),
                String.valueOf(record.section()),
                String.valueOf(record.filingDate()),
                String.valueOf(start),
                String.valueOf(end));
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
