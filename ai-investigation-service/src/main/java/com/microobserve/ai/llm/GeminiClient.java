package com.microobserve.ai.llm;

import com.microobserve.ai.config.AiProperties;
import com.microobserve.ai.model.RootCauseHypothesis;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@Component
public class GeminiClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AiProperties properties;

    public GeminiClient(RestClient.Builder builder, ObjectMapper objectMapper, AiProperties properties) {
        this.restClient = builder.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<Double> embed(String text) {
        requireApiKey();
        String url = "%s/models/%s:embedContent".formatted(properties.baseUrl(), properties.embeddingModel());
        var body = Map.of(
                "content", Map.of("parts", List.of(Map.of("text", text))),
                "output_dimensionality", properties.embeddingDimensions());
        JsonNode response = restClient.post().uri(url)
                .header("x-goog-api-key", properties.apiKey())
                .body(body).retrieve().body(JsonNode.class);
        var values = response.path("embedding").path("values");
        return values.valueStream().map(JsonNode::asDouble).toList();
    }

    public RootCauseHypothesis generateStructured(String prompt) {
        requireApiKey();
        String url = "%s/models/%s:generateContent".formatted(properties.baseUrl(), properties.generationModel());
        var schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "probableCause", Map.of("type", "string"),
                        "confidence", Map.of("type", "number", "minimum", 0, "maximum", 1),
                        "supportingEvidence", stringArray(),
                        "counterEvidence", stringArray(),
                        "affectedServices", stringArray(),
                        "recommendations", stringArray(),
                        "missingInformation", stringArray()),
                "required", List.of("probableCause", "confidence", "supportingEvidence", "counterEvidence",
                        "affectedServices", "recommendations", "missingInformation"));
        var body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "temperature", 0.1,
                        "responseMimeType", "application/json",
                        "responseJsonSchema", schema));
        JsonNode response = restClient.post().uri(url)
                .header("x-goog-api-key", properties.apiKey())
                .body(body).retrieve().body(JsonNode.class);
        String json = response.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
        try {
            return objectMapper.readValue(json, RootCauseHypothesis.class);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Gemini returned invalid RCA JSON", exception);
        }
    }

    private static Map<String, Object> stringArray() {
        return Map.of("type", "array", "items", Map.of("type", "string"));
    }

    private void requireApiKey() {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is required when AI is enabled");
        }
    }
}
