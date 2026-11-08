package com.fast.knowledge.integration;

import com.fasterxml.jackson.databind.*;
import com.fast.knowledge.common.ApiResponse;
import com.fast.knowledge.config.KnowledgeProperties;
import com.fast.knowledge.model.entity.KbUser;
import com.fast.knowledge.mapper.UserMapper;
import com.fast.knowledge.security.JwtUtil;
import com.fast.knowledge.storage.StorageProvider;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:course_rag_test_b01_runtime;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "knowledge.llm.base-url=", "knowledge.llm.api-key=", "knowledge.security.rate-limit-enabled=false","course.ingest.enabled=false"})
@ActiveProfiles({"bundle", "h2"})
@Import(CourseRuntimeTest.Probes.class)
class CourseRuntimeTest {
    private static final String PASSWORD = "Fixture_" + UUID.randomUUID();
    private static final String SIGNING_KEY = UUID.randomUUID().toString() + UUID.randomUUID();
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("knowledge.jwt.secret", () -> SIGNING_KEY);
        properties.add("APP_ADMIN_USERNAME", () -> "b01_admin");
        properties.add("APP_ADMIN_PASSWORD", () -> PASSWORD);
        properties.add("knowledge.storage.local.base-dir", () -> "target/b01-files-" + SIGNING_KEY.substring(0, 8));
    }
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserMapper users;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtUtil jwt;
    @Autowired StorageProvider storage;

    private JsonNode body(ResponseEntity<String> response) throws Exception {
        JsonNode result = json.readTree(response.getBody());
        assertNotNull(result.get("requestId"));
        assertEquals(response.getHeaders().getFirst("X-Request-Id"), result.path("requestId").asText());
        assertTrue(result.path("requestId").asText().matches("[0-9a-f-]{36}"));
        return result;
    }
    private String token() throws Exception {
        var response = http.postForEntity("/api/auth/login", Map.of("username", "b01_admin", "password", PASSWORD), String.class);
        assertEquals(200, response.getStatusCode().value());
        return body(response).path("data").path("token").asText();
    }
    private HttpHeaders headers(String token) {
        var headers = new HttpHeaders(); headers.setBearerAuth(token); return headers;
    }
    private ResponseEntity<String> get(String path, String token) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
    }
    @Test
    void noExternalAdaptersOrLegacySchedulesAreCreated() {
        assertTrue(context.getBeansOfType(EmbeddingModel.class).isEmpty());
        assertTrue(context.getBeansOfType(org.springframework.data.redis.connection.RedisConnectionFactory.class).isEmpty());
        assertTrue(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().isEmpty());
        for (String name : List.of("embeddingWarmup", "documentIngestServiceImpl", "wikiCompileService", "mcpController",
                "chatController", "llmConfigController", "minioStorageProvider", "ossStorageProvider", "dataInitializer")) {
            assertFalse(context.containsBean(name), name);
        }
        assertEquals("local", context.getBean(KnowledgeProperties.class).getStorage().getProvider());
        assertEquals("caffeine", context.getBean(KnowledgeProperties.class).getCache().getProvider());
        assertEquals("", context.getBean(KnowledgeProperties.class).getLlm().getApiKey());
        assertEquals(200, http.getForEntity("/api/actuator/health", String.class).getStatusCode().value());
    }
    @Test
    void loginAndErrorsUseHttpStatusesAndServerRequestIds() throws Exception {
        var h = new HttpHeaders(); h.set("X-Request-Id", "untrusted-client-id"); h.setAccept(List.of(MediaType.TEXT_HTML));
        var response = http.exchange("/api/documents", HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertEquals(401, response.getStatusCode().value());
        assertEquals(40101, body(response).path("code").asInt());
        assertNotEquals("untrusted-client-id", body(response).path("requestId").asText());
        var bad = http.postForEntity("/api/auth/login", Map.of("username", "missing", "password", "wrong"), String.class);
        assertEquals(401, bad.getStatusCode().value()); assertEquals(40102, body(bad).path("code").asInt());
        var invalid = http.postForEntity("/api/auth/login", Map.of("username", "b01_admin", "password", PASSWORD, "role", "ADMIN"), String.class);
        assertEquals(422, invalid.getStatusCode().value()); assertEquals(42201, body(invalid).path("code").asInt());
        String token = token();
        assertEquals("ADMIN", body(get("/api/auth/me", token)).path("data").path("role").asText());
        var list = get("/api/documents", token);
        assertEquals(0, body(list).path("code").asInt()); assertEquals(10, body(list).path("data").path("pageSize").asInt());
        assertEquals(422, get("/api/documents?pageSize=51", token).getStatusCode().value());
        assertEquals(422, get("/api/documents?kbId=1", token).getStatusCode().value());
        var failed = get("/api/b01-test/failure", token);
        assertEquals(500, failed.getStatusCode().value()); assertEquals(50001, body(failed).path("code").asInt());
        assertFalse(failed.getBody().contains("secret-value")); assertFalse(failed.getBody().contains("SELECT"));
    }
    @Test
    void apiAndMissingAssetsNeverFallBackToHtml() throws Exception {
        String token = token();
        for (String path : List.of("/api", "/api/", "/api/v1/documents", "/api/api/documents", "/api/unknown",
                "/api/chat", "/api/mcp", "/api/kbs", "/api/system/llm-config", "/api/v3/api-docs", "/api/swagger-ui.html", "/api/auth/setup", "/api/auth/ldap/login")) {
            HttpHeaders h = headers(token); h.setAccept(List.of(MediaType.TEXT_HTML)); h.set("Sec-Fetch-Dest", "document");
            var response = http.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class);
            assertEquals(404, response.getStatusCode().value(), path);
            assertEquals(40401, body(response).path("code").asInt(), path);
            assertFalse(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML), path);
        }
        for (String path : List.of("/", "/qa", "/login", "/register", "/documents", "/documents/12",
                "/history", "/history/12", "/favorites", "/403", "/admin/documents", "/admin/categories",
                "/admin/users", "/admin/feedback", "/admin/stats")) {
            var response = http.getForEntity(path, String.class);
            assertEquals(200, response.getStatusCode().value(), path);
            assertTrue(response.getBody().contains("B01 test shell"));
        }
        assertEquals(200, http.getForEntity("/assets/b01-runtime.js", String.class).getStatusCode().value());
        for (String path : List.of("/assets/b01-missing.js", "/unknown-route", "/assets/no-extension", "/writer",
                "/dashboard", "/users", "/setup", "/login/callback", "/kbs")) {
            var response = http.getForEntity(path, String.class);
            assertEquals(404, response.getStatusCode().value(), path);
            if (path.startsWith("/assets/")) {
                assertTrue(response.getHeaders().getContentType() == null ||
                        !response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML), path);
            } else {
                assertTrue(response.getBody().contains("href=\"/documents\""), path);
                assertFalse(response.getBody().contains("B01 test shell"), path);
            }
        }
    }
    @Test
    void uploadQueuesRealVersionAndReadyFixtureDownloadsOriginalBytes() throws Exception {
        String token = token();
        var category = http.postForEntity("/api/categories", new HttpEntity<>(
                Map.of("name", "B01_" + UUID.randomUUID(), "sortOrder", 0), headers(token)), String.class);
        assertEquals(201, category.getStatusCode().value());
        long categoryId = body(category).path("data").path("id").asLong();
        byte[] bytes = "B01 storage fixture, not a parser acceptance test.".getBytes(StandardCharsets.UTF_8);
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("title", "B01 fixture"); form.add("categoryId", categoryId);
        form.add("file", new ByteArrayResource(bytes) { @Override public String getFilename() { return "fixture.txt"; } });
        HttpHeaders h = headers(token); h.setContentType(MediaType.MULTIPART_FORM_DATA);
        var response = http.postForEntity("/api/documents", new HttpEntity<>(form, h), String.class);
        assertEquals(202, response.getStatusCode().value(), response.getBody());
        var data = body(response).path("data"); long id = data.path("id").asLong();
        long versionId = data.path("latestVersion").path("id").asLong();
        assertTrue(data.path("currentVersion").isNull()); assertEquals("QUEUED", data.path("latestVersion").path("status").asText());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM kb_index_task WHERE version_id=? AND status='QUEUED'", Integer.class, versionId));
        assertEquals(404, get("/api/documents/" + id + "/download", token).getStatusCode().value());
        String key = jdbc.queryForObject("SELECT object_key FROM kb_document_version WHERE id=?", String.class, versionId);
        try (var in = storage.openInputStream(key)) { assertArrayEquals(bytes, in.readAllBytes()); }
        // Explicit READY fixture: B01 does not pretend the B03 parser has run.
        jdbc.update("UPDATE kb_document_version SET status='READY' WHERE id=?", versionId);
        jdbc.update("UPDATE kb_document SET current_version_id=? WHERE id=?", versionId, id);
        var downloaded = http.exchange("/api/documents/" + id + "/download", HttpMethod.GET, new HttpEntity<>(headers(token)), byte[].class);
        assertEquals(200, downloaded.getStatusCode().value()); assertArrayEquals(bytes, downloaded.getBody());
        assertTrue(downloaded.getHeaders().getFirst("Content-Disposition").contains("attachment"));
        assertNotNull(downloaded.getHeaders().getFirst("X-Request-Id"));
        storage.delete(key);
        var missing = get("/api/documents/" + id + "/download", token);
        assertEquals(404, missing.getStatusCode().value()); assertEquals(40402, body(missing).path("code").asInt());
    }
    @Test
    void studentCannotUploadOrUseAdminListAndLogoutRevokesToken() throws Exception {
        KbUser user = new KbUser(); String name = "b01_user_" + UUID.randomUUID().toString().substring(0, 6);
        user.setUsername(name); user.setPassword(passwords.encode(PASSWORD)); user.setDisplayName(name);
        user.setRole("USER"); user.setStatus(1); user.setAuthSource("LOCAL"); user.setMustChangePassword(0); users.insert(user);
        String token = jwt.createToken(user.getId(), user.getUsername(), "ADMIN"); // DB role wins.
        var denied = get("/api/admin/documents", token);
        assertEquals(403, denied.getStatusCode().value()); assertEquals(40301, body(denied).path("code").asInt());
        var upload = http.exchange("/api/documents", HttpMethod.POST, new HttpEntity<>(headers(token)), String.class);
        assertEquals(403, upload.getStatusCode().value());
        var logout = http.postForEntity("/api/auth/logout", new HttpEntity<>(headers(token)), String.class);
        assertEquals(200, logout.getStatusCode().value());
        assertEquals(401, get("/api/auth/me", token).getStatusCode().value());
    }
    @TestConfiguration
    static class Probes {
        @Bean FailureController failureController() { return new FailureController(); }
    }
    @RestController
    static class FailureController {
        @GetMapping("/b01-test/failure")
        ApiResponse<Void> failure() { throw new IllegalStateException("SELECT secret-value FROM private_path"); }
    }
}
