package com.fulinlin.service;

import com.fulinlin.model.LlmProfile;
import com.fulinlin.model.LlmSettings;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

class AnthropicLlmProviderClient extends AbstractHttpLlmProviderClient {

    static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final Gson GSON = new Gson();

    @Override
    @NotNull
    public String chat(@NotNull LlmProfile profile,
                       @NotNull LlmSettings settings,
                       @NotNull String systemPrompt,
                       @NotNull String userPrompt) throws IOException {
        HttpURLConnection connection = createConnection(profile);
        JsonObject requestBody = createRequestBody(profile, settings, systemPrompt, userPrompt, false);
        write(connection, GSON.toJson(requestBody));

        int responseCode = connection.getResponseCode();
        InputStream inputStream = responseCode >= 200 && responseCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (responseCode < 200 || responseCode >= 300) {
            throw new IOException(extractErrorMessage(readAll(inputStream)));
        }

        try {
            String responseBody = readAll(inputStream);
            String contentType = connection.getHeaderField("Content-Type");
            if (isEventStream(contentType, responseBody)) {
                return extractChatResponseFromEventStream(responseBody);
            }
            return extractChatResponse(responseBody);
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public void streamChat(@NotNull LlmProfile profile,
                           @NotNull LlmSettings settings,
                           @NotNull String systemPrompt,
                           @NotNull String userPrompt,
                           @NotNull Consumer<String> onDelta) throws IOException {
        HttpURLConnection connection = createConnection(profile);
        JsonObject requestBody = createRequestBody(profile, settings, systemPrompt, userPrompt, true);
        write(connection, GSON.toJson(requestBody));

        int responseCode = connection.getResponseCode();
        InputStream inputStream = responseCode >= 200 && responseCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (responseCode < 200 || responseCode >= 300) {
            throw new IOException(extractErrorMessage(readAll(inputStream)));
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            String currentEvent = "";
            while ((line = reader.readLine()) != null) {
                String normalizedLine = normalizeEventStreamLine(line);
                if (normalizedLine.isEmpty()) {
                    continue;
                }
                if (normalizedLine.startsWith("event:")) {
                    currentEvent = normalizedLine.substring(6).trim();
                    continue;
                }
                if (!normalizedLine.startsWith("data:")) {
                    continue;
                }
                String payload = normalizedLine.substring(5).trim();
                if (payload.startsWith("event:")) {
                    currentEvent = payload.substring(6).trim();
                    continue;
                }
                if (payload.startsWith("data:")) {
                    payload = payload.substring(5).trim();
                }
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                String delta = extractStreamDelta(currentEvent, payload);
                if (!delta.isEmpty()) {
                    onDelta.accept(delta);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    @NotNull
    static JsonObject createRequestBody(@NotNull LlmProfile profile,
                                        @NotNull LlmSettings settings,
                                        @NotNull String systemPrompt,
                                        @NotNull String userPrompt,
                                        boolean stream) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", profile.getModel().trim());
        requestBody.addProperty("system", systemPrompt);
        requestBody.addProperty("stream", stream);
        requestBody.addProperty("max_tokens", 1024);
        if (settings.getTemperature() != null) {
            requestBody.addProperty("temperature", settings.getTemperature());
        }
        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", userPrompt);
        messages.add(userMessage);
        requestBody.add("messages", messages);
        return requestBody;
    }

    @NotNull
    static String extractChatResponse(@NotNull String responseBody) {
        JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
        if (!jsonObject.has("content") || jsonObject.get("content").isJsonNull()) {
            return "";
        }
        return new AnthropicLlmProviderClient().extractTextParts(jsonObject.get("content"));
    }

    @NotNull
    static String extractChatResponseFromEventStream(@NotNull String responseBody) {
        StringBuilder builder = new StringBuilder();
        String currentEvent = "";
        for (String rawLine : responseBody.split("\\R")) {
            String line = normalizeEventStreamLine(rawLine);
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("event:")) {
                currentEvent = line.substring(6).trim();
                continue;
            }
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring(5).trim();
            if (payload.startsWith("event:")) {
                currentEvent = payload.substring(6).trim();
                continue;
            }
            if (payload.startsWith("data:")) {
                payload = payload.substring(5).trim();
            }
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            builder.append(extractStreamDelta(currentEvent, payload));
        }
        return builder.toString();
    }

    @NotNull
    static String extractStreamDelta(@NotNull String eventType, @NotNull String payload) {
        JsonObject jsonObject = JsonParser.parseString(payload).getAsJsonObject();
        if ("content_block_delta".equals(eventType)
                || (jsonObject.has("type") && "content_block_delta".equals(jsonObject.get("type").getAsString()))) {
            JsonObject delta = jsonObject.has("delta") ? jsonObject.getAsJsonObject("delta") : null;
            if (delta != null && delta.has("text") && !delta.get("text").isJsonNull()) {
                return delta.get("text").getAsString();
            }
        }
        if ("message_delta".equals(eventType)
                || "message_stop".equals(eventType)
                || "ping".equals(eventType)
                || "content_block_stop".equals(eventType)) {
            return "";
        }
        if (jsonObject.has("content") && !jsonObject.get("content").isJsonNull()) {
            return new AnthropicLlmProviderClient().extractTextParts(jsonObject.get("content"));
        }
        return "";
    }

    @NotNull
    public String extractErrorMessage(@NotNull String responseBody) {
        try {
            JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonObject error = jsonObject.has("error") ? jsonObject.getAsJsonObject("error") : null;
            if (error != null && error.has("message") && !error.get("message").isJsonNull()) {
                return error.get("message").getAsString();
            }
        } catch (RuntimeException ignored) {
            // Fallback to raw response body when the provider returns non-JSON content.
        }
        return responseBody;
    }

    @NotNull
    private HttpURLConnection createConnection(@NotNull LlmProfile profile) throws IOException {
        HttpURLConnection connection = openPostConnection(resolveMessagesEndpoint(profile));
        connection.setRequestProperty("Authorization", "Bearer " + profile.getApiKey().trim());
        connection.setRequestProperty("x-api-key", profile.getApiKey().trim());
        connection.setRequestProperty("anthropic-version", ANTHROPIC_VERSION);
        return connection;
    }

    @NotNull
    static String resolveMessagesEndpoint(@NotNull LlmProfile profile) {
        String baseUrl = profile.getBaseUrl().trim();
        if (baseUrl.endsWith("/v1/messages")) {
            return baseUrl;
        }
        if (baseUrl.endsWith("/v1")) {
            return baseUrl + "/messages";
        }
        return new AnthropicLlmProviderClient().resolveEndpoint(profile, "/v1/messages");
    }

    static boolean isEventStream(String contentType, @NotNull String responseBody) {
        return contentType != null && contentType.contains("text/event-stream")
                || responseBody.startsWith("data:");
    }

    @NotNull
    static String normalizeEventStreamLine(@NotNull String line) {
        String normalized = line.trim();
        while (normalized.startsWith("data:event:")) {
            normalized = normalized.substring(5).trim();
        }
        return normalized;
    }
}
