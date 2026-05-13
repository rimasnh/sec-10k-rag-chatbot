package com.sec.rag.ingestion;

import com.sec.rag.config.AppProperties;
import com.sec.rag.dto.IngestionResponse;
import com.sec.rag.rag.QdrantService;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final AppProperties properties;
    private final ParquetFilingReader parquetFilingReader;
    private final TextChunker textChunker;
    private final QdrantService qdrantService;

    public IngestionService(AppProperties properties,
                            ParquetFilingReader parquetFilingReader,
                            TextChunker textChunker,
                            QdrantService qdrantService) {
        this.properties = properties;
        this.parquetFilingReader = parquetFilingReader;
        this.textChunker = textChunker;
        this.qdrantService = qdrantService;
    }

    @PostConstruct
    public void autoIngestIfEnabled() {
        if (properties.getData().isAutoIngest()) {
            log.info("AUTO_INGEST enabled, starting ingestion");
            ingestAll();
        }
    }

    public IngestionResponse ingestAll() {
        long startNanos = System.nanoTime();
        Path dataDir = Path.of(properties.getData().getDir());
        log.info("Starting ingestion dataDir='{}' autoIngest={}", dataDir.toAbsolutePath(), properties.getData().isAutoIngest());
        if (!Files.isDirectory(dataDir)) {
            log.warn("Ingestion aborted because data directory does not exist: {}", dataDir.toAbsolutePath());
            return new IngestionResponse(false, 0, 0,
                    "Data directory not found: " + dataDir.toAbsolutePath());
        }

        try {
            List<Path> parquetFiles;
            try (Stream<Path> stream = Files.list(dataDir)) {
                parquetFiles = stream
                        .filter(path -> path.getFileName().toString().endsWith(".parquet"))
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
            }

            log.info("Discovered {} parquet files for ingestion", parquetFiles.size());

            qdrantService.ensureCollectionExists();

            int indexedChunks = 0;
            for (Path parquetFile : parquetFiles) {
                long fileStartNanos = System.nanoTime();
                List<FilingRecord> records = parquetFilingReader.read(parquetFile);
                List<ChunkedSection> chunks = new ArrayList<>();
                for (FilingRecord record : records) {
                    chunks.addAll(textChunker.chunk(record));
                }
                qdrantService.upsert(chunks);
                indexedChunks += chunks.size();
                log.info("Indexed file='{}' records={} chunks={} durationMs={}",
                        parquetFile.getFileName(),
                        records.size(),
                        chunks.size(),
                        elapsedMillis(fileStartNanos));
            }

            log.info("Ingestion completed filesProcessed={} recordsIndexed={} durationMs={}",
                    parquetFiles.size(),
                    indexedChunks,
                    elapsedMillis(startNanos));
            return new IngestionResponse(true, parquetFiles.size(), indexedChunks,
                    "Ingestion completed successfully");
        } catch (IOException exception) {
            log.error("Failed to ingest parquet files", exception);
            return new IngestionResponse(false, 0, 0, exception.getMessage());
        }
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
