package com.sec.rag.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Ollama ollama = new Ollama();
    private final Qdrant qdrant = new Qdrant();
    private final Data data = new Data();
    private final Blob blob = new Blob();
    private final Rag rag = new Rag();
    private final Cors cors = new Cors();
    private final Debug debug = new Debug();
    private final RequestTracing requestTracing = new RequestTracing();

    public Ollama getOllama() {
        return ollama;
    }

    public Qdrant getQdrant() {
        return qdrant;
    }

    public Data getData() {
        return data;
    }

    public Rag getRag() {
        return rag;
    }

    public Blob getBlob() {
        return blob;
    }

    public Cors getCors() {
        return cors;
    }

    public Debug getDebug() {
        return debug;
    }

    public RequestTracing getRequestTracing() {
        return requestTracing;
    }

    public static class Ollama {
        @NotBlank
        private String baseUrl;
        @NotBlank
        private String chatModel;
        @NotBlank
        private String embeddingModel;
        @Min(30)
        private int timeoutSeconds = 120;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getChatModel() {
            return chatModel;
        }

        public void setChatModel(String chatModel) {
            this.chatModel = chatModel;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public static class Qdrant {
        @NotBlank
        private String url;
        @NotBlank
        private String collection;
        @Min(1)
        private int dimension = 768;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getCollection() {
            return collection;
        }

        public void setCollection(String collection) {
            this.collection = collection;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }
    }

    public static class Data {
        @NotBlank
        private String dir;
        private boolean autoIngest;
        private String source = "local";

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }

        public boolean isAutoIngest() {
            return autoIngest;
        }

        public void setAutoIngest(boolean autoIngest) {
            this.autoIngest = autoIngest;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }
    }

    public static class Blob {
        private String connectionString;
        private String containerName = "10kfilestorage";

        public String getConnectionString() {
            return connectionString;
        }

        public void setConnectionString(String connectionString) {
            this.connectionString = connectionString;
        }

        public String getContainerName() {
            return containerName;
        }

        public void setContainerName(String containerName) {
            this.containerName = containerName;
        }
    }

    public static class Rag {
        @Min(1)
        private int defaultTopK = 5;
        @Min(1)
        private int maxContextChunks = 6;
        @Min(100)
        private int chunkSizeTokens = 800;
        @Min(10)
        private int chunkOverlapTokens = 120;

        public int getDefaultTopK() {
            return defaultTopK;
        }

        public void setDefaultTopK(int defaultTopK) {
            this.defaultTopK = defaultTopK;
        }

        public int getMaxContextChunks() {
            return maxContextChunks;
        }

        public void setMaxContextChunks(int maxContextChunks) {
            this.maxContextChunks = maxContextChunks;
        }

        public int getChunkSizeTokens() {
            return chunkSizeTokens;
        }

        public void setChunkSizeTokens(int chunkSizeTokens) {
            this.chunkSizeTokens = chunkSizeTokens;
        }

        public int getChunkOverlapTokens() {
            return chunkOverlapTokens;
        }

        public void setChunkOverlapTokens(int chunkOverlapTokens) {
            this.chunkOverlapTokens = chunkOverlapTokens;
        }
    }

    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>();

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    public static class Debug {
        private boolean logLlmPrompts;

        public boolean isLogLlmPrompts() {
            return logLlmPrompts;
        }

        public void setLogLlmPrompts(boolean logLlmPrompts) {
            this.logLlmPrompts = logLlmPrompts;
        }
    }

    public static class RequestTracing {
        @NotBlank
        private String headerName = "X-Correlation-Id";
        private boolean includeResponseHeader = true;

        public String getHeaderName() {
            return headerName;
        }

        public void setHeaderName(String headerName) {
            this.headerName = headerName;
        }

        public boolean isIncludeResponseHeader() {
            return includeResponseHeader;
        }

        public void setIncludeResponseHeader(boolean includeResponseHeader) {
            this.includeResponseHeader = includeResponseHeader;
        }
    }
}
