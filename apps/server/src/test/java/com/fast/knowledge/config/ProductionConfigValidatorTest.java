package com.fast.knowledge.config;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
class ProductionConfigValidatorTest {
    @Test void missingAndPublicJwtKeysRefuseStartupEvenWithoutJwtEnvironmentVariable() {
        var properties = new KnowledgeProperties();
        var validator = new ProductionConfigValidator(properties, new MockEnvironment());
        assertThrows(IllegalStateException.class, validator::validateOnStartup);
        properties.getJwt().setSecret("fast-knowledge-jwt-secret-change-in-production-32chars");
        assertThrows(IllegalStateException.class, validator::validateOnStartup);
        properties.getJwt().setSecret(java.util.UUID.randomUUID().toString());
        assertDoesNotThrow(validator::validateOnStartup);
    }
}
