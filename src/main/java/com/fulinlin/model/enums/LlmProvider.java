package com.fulinlin.model.enums;

import com.fulinlin.localization.PluginBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public enum LlmProvider {
    OPENAI_COMPATIBLE("setting.llm.provider.openai", "https://api.openai.com/v1"),
    ANTHROPIC("setting.llm.provider.anthropic", "https://api.anthropic.com");

    private final String displayKey;
    private final String defaultBaseUrl;

    LlmProvider(String displayKey, String defaultBaseUrl) {
        this.displayKey = displayKey;
        this.defaultBaseUrl = defaultBaseUrl;
    }

    @NotNull
    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    @NotNull
    public String getDisplayName() {
        return PluginBundle.get(displayKey);
    }

    @NotNull
    public static LlmProvider defaultProvider() {
        return OPENAI_COMPATIBLE;
    }

    @NotNull
    public static LlmProvider fromNullable(@Nullable LlmProvider provider) {
        return provider == null ? defaultProvider() : provider;
    }
}
