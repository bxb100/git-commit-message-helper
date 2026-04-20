package com.fulinlin.service;

import com.fulinlin.model.LlmProfile;
import com.fulinlin.model.LlmSettings;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.function.Consumer;

interface LlmProviderClient {

    @NotNull
    String chat(@NotNull LlmProfile profile,
                @NotNull LlmSettings settings,
                @NotNull String systemPrompt,
                @NotNull String userPrompt) throws IOException;

    void streamChat(@NotNull LlmProfile profile,
                    @NotNull LlmSettings settings,
                    @NotNull String systemPrompt,
                    @NotNull String userPrompt,
                    @NotNull Consumer<String> onDelta) throws IOException;

    @NotNull
    String extractErrorMessage(@NotNull String responseBody);
}
