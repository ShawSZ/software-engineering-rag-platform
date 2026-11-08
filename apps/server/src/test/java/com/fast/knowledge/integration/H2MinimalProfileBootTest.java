package com.fast.knowledge.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * h2 单机极简 profile 启动冒烟：不依赖 MySQL / Redis / MinIO（Docker 也无需），
 * 验证完整 Spring 上下文可启动、schema-h2.sql 全量建表成功、
 * Caffeine 缓存与本地目录存储提供方正常装配。
 */
@Tag("integration")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:course_rag_test_h2boot;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "knowledge.jwt.secret=h2-boot-test-signing-material-only-1234567890",
        "APP_ADMIN_USERNAME=",
        "APP_ADMIN_PASSWORD=",
        "knowledge.cache.provider=caffeine",
        "knowledge.storage.provider=local",
        "knowledge.storage.local.base-dir=target/h2boot-files",
        "knowledge.vector.local.storage-dir=target/h2boot-vectors",
        "knowledge.embedding.provider=hash",
        "knowledge.index.pubsub-enabled=false",
        "knowledge.search.keyword-enabled=false",
        "server.port=0"
})
@ActiveProfiles("h2")
class H2MinimalProfileBootTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void bootsWithoutExternalMiddlewareAndSchemaApplied() {
        // 没有本地初始化配置时，不创建默认账号。
        Integer userCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM kb_user", Integer.class);
        assertNotNull(userCount);
        assertEquals(0, userCount, "未提供初始化配置时不创建默认管理员");

        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'PUBLIC' AND table_name LIKE 'kb%'",
                Integer.class);
        assertNotNull(tableCount);
        assertTrue(tableCount >= 20, "期望至少 20 张 kb_* 业务表，实际 " + tableCount);
    }
}
