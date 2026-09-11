package cn.watsontech.snapagent.boot2x.autoconfig;

import cn.watsontech.snapagent.core.embedding.EmbeddingException;
import cn.watsontech.snapagent.core.embedding.EmbeddingModel;
import cn.watsontech.snapagent.core.vectorstore.InMemoryVectorStore;
import cn.watsontech.snapagent.core.vectorstore.VectorStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Auto-configuration for embedding model and vector store (2.x).
 *
 * <p>Creates beans when {@code snap-agent.vectorstore.enabled=true}:</p>
 * <ul>
 *   <li>{@link EmbeddingModel} - text embedding provider (OpenAI or Ollama)</li>
 *   <li>{@link VectorStore} - vector storage (in-memory by default)</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(prefix = "snap-agent.vectorstore", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SnapAgentProperties.class)
public class EmbeddingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingAutoConfiguration.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Create an EmbeddingModel bean based on configuration.
     * Supports OpenAI and Ollama providers.
     */
    @Bean
    @ConditionalOnMissingBean
    public EmbeddingModel embeddingModel(SnapAgentProperties props) {
        SnapAgentProperties.Embedding embeddingProps = props.getEmbedding();
        String provider = embeddingProps.getProvider();

        log.info("EmbeddingModel assembling (provider={}, model={})", provider, embeddingProps.getModel());

        if ("ollama".equalsIgnoreCase(provider)) {
            // Ollama embedding
            String baseUrl = embeddingProps.getOllama().getBaseUrl();
            String model = embeddingProps.getOllama().getModel();
            return new OllamaEmbeddingModel(baseUrl, model);
        } else {
            // OpenAI embedding (default)
            String apiKey = embeddingProps.getOpenai().getApiKey();
            String baseUrl = embeddingProps.getOpenai().getBaseUrl();
            String model = embeddingProps.getModel();
            return new OpenAiEmbeddingModel(apiKey, baseUrl, model);
        }
    }

    /**
     * Create a VectorStore bean.
     * Default is InMemoryVectorStore.
     */
    @Bean
    @ConditionalOnMissingBean
    public VectorStore vectorStore() {
        log.info("VectorStore assembling (type=in-memory)");
        return new InMemoryVectorStore();
    }

    /**
     * OpenAI-compatible embedding model implementation.
     * Calls the /v1/embeddings endpoint.
     */
    private static class OpenAiEmbeddingModel implements EmbeddingModel {
        private final String apiKey;
        private final String baseUrl;
        private final String model;

        OpenAiEmbeddingModel(String apiKey, String baseUrl, String model) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl != null && !baseUrl.isEmpty() ? baseUrl : "https://api.openai.com/v1";
            this.model = model;
        }

        @Override
        public float[] embed(String text) {
            try {
                String url = baseUrl.endsWith("/") ? baseUrl + "embeddings" : baseUrl + "/embeddings";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                if (apiKey != null && !apiKey.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                }
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);

                // Build request body
                java.util.Map<String, Object> requestBodyMap = new java.util.HashMap<>();
                requestBodyMap.put("input", text);
                requestBodyMap.put("model", model);
                String requestBody = MAPPER.writeValueAsString(requestBodyMap);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(requestBody.getBytes(StandardCharsets.UTF_8));
                }

                // Read response
                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    throw new EmbeddingException("OpenAI embedding API returned " + responseCode
                            + ": " + conn.getResponseMessage());
                }

                String responseBody;
                try (Scanner scanner = new Scanner(conn.getInputStream(), StandardCharsets.UTF_8.name())) {
                    responseBody = scanner.useDelimiter("\\A").next();
                }

                // Parse response
                JsonNode root = MAPPER.readTree(responseBody);
                JsonNode data = root.path("data");
                if (data.isArray() && data.size() > 0) {
                    JsonNode embedding = data.get(0).path("embedding");
                    if (embedding.isArray()) {
                        float[] vector = new float[embedding.size()];
                        for (int i = 0; i < embedding.size(); i++) {
                            vector[i] = (float) embedding.get(i).asDouble();
                        }
                        return vector;
                    }
                }

                throw new EmbeddingException("Failed to parse OpenAI embedding response");

            } catch (EmbeddingException e) {
                throw e;
            } catch (Exception e) {
                throw new EmbeddingException("OpenAI embedding call failed: " + e.getMessage(), e);
            }
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            List<float[]> vectors = new ArrayList<>();
            for (String text : texts) {
                vectors.add(embed(text));
            }
            return vectors;
        }
    }

    /**
     * Ollama embedding model implementation.
     * Calls the /api/embeddings endpoint.
     */
    private static class OllamaEmbeddingModel implements EmbeddingModel {
        private final String baseUrl;
        private final String model;

        OllamaEmbeddingModel(String baseUrl, String model) {
            this.baseUrl = baseUrl;
            this.model = model;
        }

        @Override
        public float[] embed(String text) {
            try {
                String url = baseUrl.endsWith("/") ? baseUrl + "api/embeddings" : baseUrl + "/api/embeddings";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);

                // Build request body
                java.util.Map<String, Object> requestBodyMap = new java.util.HashMap<>();
                requestBodyMap.put("model", model);
                requestBodyMap.put("prompt", text);
                String requestBody = MAPPER.writeValueAsString(requestBodyMap);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(requestBody.getBytes(StandardCharsets.UTF_8));
                }

                // Read response
                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    throw new EmbeddingException("Ollama embedding API returned " + responseCode
                            + ": " + conn.getResponseMessage());
                }

                String responseBody;
                try (Scanner scanner = new Scanner(conn.getInputStream(), StandardCharsets.UTF_8.name())) {
                    responseBody = scanner.useDelimiter("\\A").next();
                }

                // Parse response
                JsonNode root = MAPPER.readTree(responseBody);
                JsonNode embedding = root.path("embedding");
                if (embedding.isArray()) {
                    float[] vector = new float[embedding.size()];
                    for (int i = 0; i < embedding.size(); i++) {
                        vector[i] = (float) embedding.get(i).asDouble();
                    }
                    return vector;
                }

                throw new EmbeddingException("Failed to parse Ollama embedding response");

            } catch (EmbeddingException e) {
                throw e;
            } catch (Exception e) {
                throw new EmbeddingException("Ollama embedding call failed: " + e.getMessage(), e);
            }
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            List<float[]> vectors = new ArrayList<>();
            for (String text : texts) {
                vectors.add(embed(text));
            }
            return vectors;
        }
    }
}
