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

class OpenAiCompatibleLlmProviderClient extends AbstractHttpLlmProviderClient {

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
            return extractChatResponse(readAll(inputStream));
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
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    break;
                }
                String deltaText = extractStreamDelta(payload);
                if (!deltaText.isEmpty()) {
                    onDelta.accept(deltaText);
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
        requestBody.addProperty("stream", stream);
        if (settings.getTemperature() != null) {
            requestBody.addProperty("temperature", settings.getTemperature());
        }
        JsonArray messages = new JsonArray();
        messages.add(createMessage("system", systemPrompt));
        messages.add(createMessage("user", userPrompt));
        requestBody.add("messages", messages);
        return requestBody;
    }

    @NotNull
    static String extractChatResponse(@NotNull String responseBody) {
        JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray choices = jsonObject.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) {
            return "";
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject message = choice.has("message") ? choice.getAsJsonObject("message") : null;
        if (message != null && message.has("content") && !message.get("content").isJsonNull()) {
            return extractContent(message);
        }
        return "";
    }

    @NotNull
    static String extractStreamDelta(@NotNull String payload) {
        JsonObject jsonObject = JsonParser.parseString(payload).getAsJsonObject();
        JsonArray choices = jsonObject.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) {
            return "";
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject delta = choice.has("delta") ? choice.getAsJsonObject("delta") : null;
        if (delta != null && delta.has("content") && !delta.get("content").isJsonNull()) {
            return delta.get("content").getAsString();
        }
        JsonObject message = choice.has("message") ? choice.getAsJsonObject("message") : null;
        if (message != null && message.has("content") && !message.get("content").isJsonNull()) {
            return extractContent(message);
        }
        return "";
    }

    @Override
    @NotNull
    public String extractErrorMessage(@NotNull String responseBody) {
        try {
            JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonObject error = jsonObject.has("error") && jsonObject.get("error").isJsonObject()
                    ? jsonObject.getAsJsonObject("error")
                    : null;
            if (error != null && error.has("message") && !error.get("message").isJsonNull()) {
                return error.get("message").getAsString();
            }
        } catch (RuntimeException ignored) {
            // Fall back to the raw body for non-JSON errors.
        }
        return responseBody;
    }

    @NotNull
    private HttpURLConnection createConnection(@NotNull LlmProfile profile) throws IOException {
        HttpURLConnection connection = openPostConnection(resolveEndpoint(profile, "/chat/completions"));
        connection.setRequestProperty("Authorization", "Bearer " + profile.getApiKey().trim());
        return connection;
    }

    @NotNull
    private static JsonObject createMessage(@NotNull String role, @NotNull String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    @NotNull
    private static String extractContent(@NotNull JsonObject message) {
        return message.get("content").isJsonPrimitive()
                ? message.get("content").getAsString()
                : new OpenAiCompatibleLlmProviderClient().extractTextParts(message.get("content"));
    }
}
