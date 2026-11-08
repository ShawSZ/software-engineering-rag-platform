package com.fast.knowledge.llm;

import com.fast.knowledge.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmModelRegistryTest {
    @Test
    void missingCloudKeyAllowsStartupButRejectsModelUse() {
        LlmConfigResolver resolver = mock(LlmConfigResolver.class);
        when(resolver.resolve()).thenReturn(config(""));
        LlmModelRegistry registry = new LlmModelRegistry(resolver);

        assertDoesNotThrow(registry::init);
        assertThrows(BusinessException.class, registry::getChatModel);
        assertThrows(BusinessException.class, registry::getStreamingChatModel);
    }

    @Test
    void clearingKeyDisablesPreviouslyConfiguredModels() {
        LlmConfigResolver resolver = mock(LlmConfigResolver.class);
        when(resolver.resolve()).thenReturn(config("test-placeholder"), config(""));
        LlmModelRegistry registry = new LlmModelRegistry(resolver);

        registry.init();
        assertNotNull(registry.getChatModel());
        assertNotNull(registry.getStreamingChatModel());
        registry.refresh();
        assertThrows(BusinessException.class, registry::getChatModel);
    }

    private ResolvedLlmConfig config(String key) {
        return ResolvedLlmConfig.builder().provider(LlmProvider.DEEPSEEK)
                .baseUrl("http://localhost:1/v1").apiKey(key).model("test-model")
                .maxTokens(64).temperature(0.3).allowExternal(true).build();
    }
}
