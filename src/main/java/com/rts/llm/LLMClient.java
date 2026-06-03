package com.rts.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Client pour appeler un LLM (OpenAI, Anthropic, ou Ollama local).
 */
public class LLMClient {

    public enum Provider { OPENAI, ANTHROPIC, OLLAMA, GEMINI }

    private final Provider provider;
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final Gson gson;

    public LLMClient(Provider provider, String apiKey, String model) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = switch (provider) {
            case OPENAI -> "https://api.openai.com/v1/chat/completions";
            case ANTHROPIC -> "https://api.anthropic.com/v1/messages";
            case OLLAMA -> "http://localhost:11434/api/chat";
            // L'URL Gemini dépend du modèle et de la clé — construite dynamiquement
            case GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models/";
        };
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.gson = new Gson();
    }

    /**
     * Envoie le prompt au LLM et retourne la réponse texte.
     */
    public String chat(String prompt) throws IOException {
        String requestBody = buildRequestBody(prompt);

        HttpRequest request = buildHttpRequest(requestBody);

        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IOException("LLM API error " + response.statusCode()
                        + ": " + response.body());
            }

            return extractResponseText(response.body());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("LLM request interrupted", e);
        }
    }

    private String buildRequestBody(String prompt) {
        JsonObject body = new JsonObject();

        switch (provider) {
            case OPENAI -> {
                body.addProperty("model", model);
                body.addProperty("max_tokens", 1000);
                body.addProperty("temperature", 0.1); // quasi-déterministe
                JsonArray messages = new JsonArray();
                JsonObject msg = new JsonObject();
                msg.addProperty("role", "user");
                msg.addProperty("content", prompt);
                messages.add(msg);
                body.add("messages", messages);
            }
            case ANTHROPIC -> {
                body.addProperty("model", model);
                body.addProperty("max_tokens", 1000);
                body.addProperty("temperature", 0.1);
                JsonArray messages = new JsonArray();
                JsonObject msg = new JsonObject();
                msg.addProperty("role", "user");
                msg.addProperty("content", prompt);
                messages.add(msg);
                body.add("messages", messages);
            }
            case OLLAMA -> {
                body.addProperty("model", model);
                body.addProperty("stream", false);
                JsonArray messages = new JsonArray();
                JsonObject msg = new JsonObject();
                msg.addProperty("role", "user");
                msg.addProperty("content", prompt);
                messages.add(msg);
                body.add("messages", messages);
            }
            case GEMINI -> {
                // Format Gemini : { "contents": [{ "parts": [{"text": "..."}] }] }
                JsonArray parts = new JsonArray();
                JsonObject part = new JsonObject();
                part.addProperty("text", prompt);
                parts.add(part);
                JsonObject contentItem = new JsonObject();
                contentItem.add("parts", parts);
                JsonArray contents = new JsonArray();
                contents.add(contentItem);
                body.add("contents", contents);
                JsonObject genConfig = new JsonObject();
                genConfig.addProperty("temperature", 0.1);
                genConfig.addProperty("maxOutputTokens", 1000);
                body.add("generationConfig", genConfig);
            }
        }

        return gson.toJson(body);
    }

    private HttpRequest buildHttpRequest(String requestBody) {
        // Pour Gemini, l'URL complète inclut le modèle et la clé API
        String url = provider == Provider.GEMINI
                ? baseUrl + model + ":generateContent?key=" + apiKey
                : baseUrl;

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(600))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));

        switch (provider) {
            case OPENAI -> builder.header("Authorization", "Bearer " + apiKey);
            case ANTHROPIC -> {
                builder.header("x-api-key", apiKey);
                builder.header("anthropic-version", "2023-06-01");
            }
            case OLLAMA -> {} // pas d'authentification
            case GEMINI -> {} // clé dans l'URL
        }

        return builder.build();
    }

    private String extractResponseText(String responseBody) {
        JsonObject json = gson.fromJson(responseBody, JsonObject.class);

        return switch (provider) {
            case OPENAI -> json.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
            case ANTHROPIC -> json.getAsJsonArray("content")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString();
            case OLLAMA -> json.getAsJsonObject("message")
                    .get("content").getAsString();
            // Gemini : { "candidates": [{ "content": { "parts": [{"text":"..."}] } }] }
            case GEMINI -> json.getAsJsonArray("candidates")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString();
        };
    }

    public Provider getProvider() { return provider; }
    public String getModel() { return model; }
}
