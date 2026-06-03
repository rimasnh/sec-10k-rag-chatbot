package com.sec.rag.ingestion;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.sec.rag.config.AppProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BlobParquetStorageService {

    private static final Logger log = LoggerFactory.getLogger(BlobParquetStorageService.class);

    private final AppProperties properties;

    public BlobParquetStorageService(AppProperties properties) {
        this.properties = properties;
    }

    public List<Path> downloadParquetFiles() throws IOException {
        String connectionString = properties.getBlob().getConnectionString();
        String containerName = properties.getBlob().getContainerName();

        if (connectionString == null || connectionString.isBlank()) {
            throw new IOException("AZURE_BLOB_CONNECTION_STRING is required for blob ingestion");
        }

        BlobContainerClient containerClient = new BlobServiceClientBuilder().connectionString(connectionString)
                .buildClient().getBlobContainerClient(containerName);

        if (!containerClient.exists()) {
            log.info("Blob container '{}' not found, creating it", containerName);
            containerClient.create();
        }

        List<BlobItem> parquetBlobs = StreamSupport.stream(containerClient.listBlobs().spliterator(), false)
                .filter(blobItem -> blobItem.getName().endsWith(".parquet"))
                .sorted(Comparator.comparing(BlobItem::getName)).toList();

        List<Path> downloadedFiles = new ArrayList<>();
        for (BlobItem blobItem : parquetBlobs) {
            Path tempFile = Files.createTempFile("filinglens-", "-" + sanitizeFileName(blobItem.getName()));
            try (var inputStream = containerClient.getBlobClient(blobItem.getName()).openInputStream()) {
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            downloadedFiles.add(tempFile);
            log.info("Downloaded blob '{}' to '{}'", blobItem.getName(), tempFile);
        }

        return downloadedFiles;
    }

    private String sanitizeFileName(String name) {
        return name.replace("/", "_").replace("\\", "_");
    }
}
