package com.fulinlin.service;

import com.fulinlin.model.LlmProfile;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

abstract class AbstractHttpLlmProviderClient implements LlmProviderClient {

    @NotNull
    protected HttpURLConnection openPostConnection(@NotNull String endpoint) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(0);
        connection.setRequestProperty("Content-Type", "application/json");
        return connection;
    }

    protected void write(@NotNull HttpURLConnection connection, @NotNull String body) throws IOException {
        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    @NotNull
    protected String readAll(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "Request failed";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
        }
        return builder.toString();
    }

    @NotNull
    protected String resolveEndpoint(@NotNull LlmProfile profile, @NotNull String expectedPath) {
        String baseUrl = profile.getBaseUrl().trim();
        return baseUrl.endsWith(expectedPath) ? baseUrl : stripTrailingSlash(baseUrl) + expectedPath;
    }

    @NotNull
    protected String stripTrailingSlash(@NotNull String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @NotNull
    protected String extractTextParts(@NotNull JsonElement content) {
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (!content.isJsonArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonElement element : content.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject part = element.getAsJsonObject();
            if (part.has("text") && !part.get("text").isJsonNull()) {
                builder.append(part.get("text").getAsString());
            }
        }
        return builder.toString();
    }
}
