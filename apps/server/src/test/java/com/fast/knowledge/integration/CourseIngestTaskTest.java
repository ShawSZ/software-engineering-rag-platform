package com.fast.knowledge.integration;

import com.fast.knowledge.course.*;
import com.fast.knowledge.common.ApiException;
import com.fast.knowledge.security.UserContext;
import com.fast.knowledge.storage.StorageProvider;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:course_rag_test_b03_tasks;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "course.ingest.enabled=false","course.ingest.lease-seconds=10","APP_ADMIN_USERNAME=","APP_ADMIN_PASSWORD=",
        "knowledge.llm.base-url=","knowledge.llm.api-key="})
@ActiveProfiles("h2")
class CourseIngestTaskTest {
    static final String RUN=UUID.randomUUID().toString();
    static final Path FILES=Path.of("target","b03-tasks-"+RUN).toAbsolutePath();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("knowledge.jwt.secret",()->RUN+UUID.randomUUID());p.add("knowledge.storage.local.base-dir",()->FILES.toString());
    }
    @Autowired CourseIngestTasks tasks;
    @Autowired CourseIngestWorker worker;
    @Autowired CourseDocumentService documents;
    @Autowired CourseCategoryService categories;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired StorageProvider storage;
    @Autowired DataSource source;
    long category;
    @BeforeEach void setup() throws Exception {
        try(var connection=source.getConnection()) {assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:course_rag_test_b03_tasks"));}
        UserContext context=new UserContext();context.setUserId(1L);context.setRole("ADMIN");UserContext.set(context);
        category=categories.save(null,"Task_"+UUID.randomUUID(),0).getId();
    }
    @AfterEach void clear() {UserContext.clear();}
    CourseDocumentService.DocumentResult upload(String text) throws Exception {
        return documents.upload(new MockMultipartFile("file","fixture.txt","text/plain",text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"Task fixture",category);
    }
    long task(long version) {return jdbc.queryForObject("SELECT id FROM kb_index_task WHERE version_id=?",Long.class,version);}
    void expire(long id) {jdbc.update("UPDATE kb_index_task SET locked_at=DATEADD('SECOND',-20,CURRENT_TIMESTAMP) WHERE id=?",id);}
    long files() throws Exception {try(var paths=Files.walk(FILES)){return paths.filter(Files::isRegularFile).count();}}
    @Test void outerTransactionRollbackRemovesOnlyNewFileAndDoesNotDispatch() throws Exception {
        long beforeFiles=files(),before=jdbc.queryForObject("SELECT COUNT(*) FROM kb_document",Long.class);
        var tx=new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class,()->tx.execute(status->{
            try {upload("rollback original");} catch(Exception e) {throw new RuntimeException(e);}
            throw new IllegalStateException("forced rollback");
        }));
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM kb_document",Long.class));assertEquals(beforeFiles,files());
        long missing=9007199254740991L;
        assertThrows(ApiException.class,()->documents.upload(new MockMultipartFile("file","fixture.txt","text/plain","missing category".getBytes()),"Missing",missing));
        assertEquals(beforeFiles,files());
    }
    @Test void duplicateWorkersCannotClaimSameTaskOrDuplicateReadyChunks() throws Exception {
        var doc=upload("😀需求".repeat(700));long id=task(doc.latestVersion().id());
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            Callable<CourseIngestTasks.Claim> action=()->{gate.await();return tasks.claim(id);};
            var a=pool.submit(action);var b=pool.submit(action);gate.countDown();
            var left=a.get(10,TimeUnit.SECONDS);var right=b.get(10,TimeUnit.SECONDS);
            assertTrue((left==null)^(right==null));
            expire(id);worker.run(id).get(15,TimeUnit.SECONDS);worker.run(id).get(15,TimeUnit.SECONDS);
            assertEquals("READY",documents.status(doc.id()).latestVersion().status());
            assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM kb_document_chunk WHERE version_id=?",Integer.class,doc.latestVersion().id()));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM kb_document_version WHERE document_id=?",Integer.class,doc.id()));
        } finally {pool.shutdownNow();}
    }
    @Test void expiredLeaseClearsPartialChunksAndFencesOldCompletionAndFailure() throws Exception {
        var doc=upload("real original");long id=task(doc.latestVersion().id());
        var old=tasks.claim(id);assertNotNull(old);
        assertTrue(tasks.append(old,List.of(new CourseFileParser.Chunk(1,null,"stale partial"))));
        assertNull(tasks.claim(id));expire(id);assertTrue(tasks.pending().contains(id));
        var current=tasks.claim(id);assertNotNull(current);assertNotEquals(old.owner(),current.owner());
        assertFalse(tasks.append(old,List.of(new CourseFileParser.Chunk(2,null,"not original"))));
        assertFalse(tasks.complete(old,1));assertFalse(tasks.fail(old,"PARSE_ERROR","stale failure"));
        assertEquals(current.owner(),jdbc.queryForObject("SELECT locked_by FROM kb_index_task WHERE id=?",String.class,id));
        assertTrue(tasks.append(current,List.of(new CourseFileParser.Chunk(1,null,"real original"))));assertTrue(tasks.complete(current,1));
        assertFalse(tasks.complete(current,1));assertEquals("real original",documents.chunks(doc.id(),1,10,null).items().getFirst().content());
    }
    @Test void failedChunkBatchRollsBackAndSeparateFailureTransactionRemainsConsistent() throws Exception {
        var doc=upload("original");long id=task(doc.latestVersion().id());var claim=tasks.claim(id);
        var chunk=new CourseFileParser.Chunk(1,null,"original");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->tasks.append(claim,List.of(chunk,chunk)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM kb_document_chunk WHERE version_id=?",Integer.class,doc.latestVersion().id()));
        assertTrue(tasks.fail(claim,"PARSE_ERROR","解析失败，请重新上传"));
        assertEquals("FAILED",documents.status(doc.id()).latestVersion().status());assertNull(documents.detail(doc.id()).currentVersion());
        assertNull(jdbc.queryForObject("SELECT locked_by FROM kb_index_task WHERE id=?",String.class,id));
    }
    @Test void repeatedlyInterruptedTaskEventuallyFailsAndCannotRemainParsingForever() throws Exception {
        var doc=upload("original");long id=task(doc.latestVersion().id());
        for(int i=0;i<3;i++) {assertNotNull(tasks.claim(id));expire(id);}
        assertNull(tasks.claim(id));assertEquals("FAILED",documents.status(doc.id()).latestVersion().status());
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM kb_index_task WHERE id=?",String.class,id));
    }
    @Test void categoryDeletionRacesWithUploadWithoutCreatingOrphansOrLeakingFiles() throws Exception {
        long beforeFiles=files();var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var upload=pool.submit(()->{
                UserContext context=new UserContext();context.setUserId(1L);context.setRole("ADMIN");UserContext.set(context);
                try {gate.await();return this.upload("concurrent file").id();}
                catch(ApiException e){assertEquals(40401,e.getCode());return null;}
                finally {UserContext.clear();}
            });
            var deletion=pool.submit(()->{gate.await();try {categories.delete(category);return true;}catch(ApiException e){assertEquals(40903,e.getCode());return false;}});
            gate.countDown();Long id=upload.get(10,TimeUnit.SECONDS);boolean deleted=deletion.get(10,TimeUnit.SECONDS);
            assertEquals(id==null,deleted);assertEquals(beforeFiles+(id==null?0:1),files());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM kb_document d LEFT JOIN kb_category c ON c.id=d.category_id WHERE d.category_id IS NOT NULL AND c.id IS NULL",Integer.class));
        } finally {pool.shutdownNow();}
    }
    @Test void missingSourceFailsWithoutPublishingReadyOrLeakingPaths() throws Exception {
        var doc=upload("original");long id=task(doc.latestVersion().id());
        storage.delete(jdbc.queryForObject("SELECT object_key FROM kb_document_version WHERE id=?",String.class,doc.latestVersion().id()));
        worker.run(id).get(10,TimeUnit.SECONDS);var result=documents.status(doc.id());
        assertEquals("FAILED",result.latestVersion().status());assertEquals("PARSE_ERROR",result.latestVersion().errorCode());
        assertFalse(result.latestVersion().errorMessage().contains(FILES.toString()));assertNull(result.currentVersion());
    }
    @Test void changedOriginalFileFailsIntegrityCheckBeforePublishingChunks() throws Exception {
        var doc=upload("original course text");long version=doc.latestVersion().id();
        String key=jdbc.queryForObject("SELECT object_key FROM kb_document_version WHERE id=?",String.class,version);
        Files.writeString(storage.readablePath(key),"changed course text");
        worker.run(task(version)).get(10,TimeUnit.SECONDS);var result=documents.status(doc.id());
        assertEquals("FAILED",result.latestVersion().status());assertEquals("PARSE_ERROR",result.latestVersion().errorCode());
        assertNull(result.currentVersion());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM kb_document_chunk WHERE version_id=?",Integer.class,version));
    }
}
