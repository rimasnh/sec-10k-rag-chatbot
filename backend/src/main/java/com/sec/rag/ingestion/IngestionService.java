package com.sec.rag.ingestion;

import com.sec.rag.config.AppProperties;
import com.sec.rag.dto.IngestionResponse;
import com.sec.rag.dto.IngestionTelemetry;
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
    private final BlobParquetStorageService blobParquetStorageService;
    private final ParquetFilingReader parquetFilingReader;
    private final TextChunker textChunker;
    private final QdrantService qdrantService;

    public IngestionService(AppProperties properties,
                            BlobParquetStorageService blobParquetStorageService,
                            ParquetFilingReader parquetFilingReader,
                            TextChunker textChunker,
                            QdrantService qdrantService) {
        this.properties = properties;
        this.blobParquetStorageService = blobParquetStorageService;
        this.parquetFilingReader = parquetFilingReader;
        this.textChunker = textChunker;
        this.qdrantService = qdrantService;
    }

    @PostConstruct
    public void autoIngestIfEnabled() {
        if (properties.getData().isAutoIngest()) {
            IngestionSource source = IngestionSource.from(properties.getData().getSource());
            log.info("AUTO_INGEST enabled, starting ingestion source={}", source);
            ingestAll(source);
        }
    }

    public IngestionResponse ingestAll() {
        return ingestAll(IngestionSource.LOCAL);
    }

    public IngestionResponse ingestAll(IngestionSource source) {
        long startNanos = System.nanoTime();
        log.info("Starting ingestion source={} autoIngest={}", source, properties.getData().isAutoIngest());
        List<Path> tempFiles = new ArrayList<>();
        try {
            List<Path> parquetFiles = resolveParquetFiles(source, tempFiles);

            log.info("Discovered {} parquet files for ingestion source={}", parquetFiles.size(), source);

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

            long ingestionDurationMs = elapsedMillis(startNanos);
            log.info("Ingestion completed filesProcessed={} recordsIndexed={} ingestionDurationMs={}",
                    parquetFiles.size(),
                    indexedChunks,
                    ingestionDurationMs);
            return new IngestionResponse(true, parquetFiles.size(), indexedChunks,
                    "Ingestion completed successfully",
                    new IngestionTelemetry(ingestionDurationMs));
        } catch (IOException exception) {
            long ingestionDurationMs = elapsedMillis(startNanos);
            log.error("Failed to ingest parquet files", exception);
            return new IngestionResponse(false, 0, 0, exception.getMessage(),
                    new IngestionTelemetry(ingestionDurationMs));
        } finally {
            cleanupTempFiles(tempFiles);
        }
    }

    private List<Path> resolveParquetFiles(IngestionSource source, List<Path> tempFiles) throws IOException {
        if (source == IngestionSource.BLOB) {
            List<Path> blobFiles = blobParquetStorageService.downloadParquetFiles();
            tempFiles.addAll(blobFiles);
            return blobFiles;
        }

        Path dataDir = Path.of(properties.getData().getDir());
        if (!Files.isDirectory(dataDir)) {
            throw new IOException("Data directory not found: " + dataDir.toAbsolutePath());
        }

        try (Stream<Path> stream = Files.list(dataDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".parquet"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private void cleanupTempFiles(List<Path> tempFiles) {
        for (Path tempFile : tempFiles) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException exception) {
                log.warn("Failed to delete temp file {}", tempFile, exception);
            }
        }
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
