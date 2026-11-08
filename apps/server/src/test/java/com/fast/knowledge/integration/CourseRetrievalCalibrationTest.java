package com.fast.knowledge.integration;

import com.fasterxml.jackson.databind.*;
import com.fast.knowledge.course.*;
import com.fast.knowledge.security.UserContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:course_rag_test_b05_calibration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "course.ingest.enabled=false","APP_ADMIN_USERNAME=","APP_ADMIN_PASSWORD=","knowledge.llm.base-url=","knowledge.llm.api-key="})
@ActiveProfiles("h2")
class CourseRetrievalCalibrationTest {
    static final String RUN=UUID.randomUUID().toString();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("knowledge.jwt.secret",()->RUN+RUN);p.add("knowledge.storage.local.base-dir",()->"target/b05-eval-"+RUN);
    }
    @Autowired CourseDocumentService documents;@Autowired CourseCategoryService categories;@Autowired CourseIngestWorker worker;
    @Autowired CourseKeywordRetriever retrieval;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
    @BeforeEach void context(){var user=new UserContext();user.setUserId(1L);user.setRole("ADMIN");UserContext.set(user);}
    @AfterEach void clear(){UserContext.clear();}
    CourseDocumentService.DocumentResult upload(String title,String text,boolean pdf,long category) throws Exception {
        byte[] bytes=pdf?CourseFixtures.pdf(false,text):text.getBytes(StandardCharsets.UTF_8);
        var doc=documents.upload(new MockMultipartFile("file",pdf?"fixture.pdf":"fixture.txt",pdf?"application/pdf":"text/plain",bytes),title,category);
        worker.run(jdbc.queryForObject("SELECT id FROM kb_index_task WHERE version_id=?",Long.class,doc.latestVersion().id())).get(15,TimeUnit.SECONDS);return doc;
    }
    @Test void fixedTwelveDocumentTwentyQuestionSetCalibratesGeneralLexicalThreshold() throws Exception {
        var fixture=json.readTree(Files.readString(Path.of("../../test-assets/b05/evaluation.json")));
        long category=categories.save(null,"Calibration "+UUID.randomUUID(),0).getId();Map<String,Long> ids=new HashMap<>();int count=0;
        for(var document:fixture.path("documents")) {
            String text=document.path("text").asText();boolean pdf=text.codePoints().noneMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN) && count%2==0;
            ids.put(document.path("key").asText(),upload(document.path("title").asText(),text,pdf,category).id());count++;
        }
        assertEquals(12,count);int positive=0,negative=0;List<Map<String,Object>> results=new ArrayList<>();
        for(var question:fixture.path("questions")) {
            String text=question.path("question").asText();var found=retrieval.retrieve(text);
            assertTrue(found.size()<=5);assertTrue(found.stream().mapToInt(e->e.content().codePointCount(0,e.content().length())).sum()<=6000);
            if(question.path("expected").isNull()) {assertTrue(found.isEmpty(),text+" unexpectedly retrieved "+found);negative++;}
            else {long expected=ids.get(question.path("expected").asText());assertTrue(found.stream().anyMatch(e->e.documentId()==expected),text+" missed "+question.path("expected")+" from "+found);positive++;}
            results.add(Map.of("question",text,"expected",question.path("expected").isNull()?"NO_EVIDENCE":question.path("expected").asText(),"retrievedTitles",found.stream().map(CourseEvidence::documentTitle).toList()));
        }
        assertEquals(15,positive);assertEquals(5,negative);Files.writeString(Path.of("target/b05-retrieval-evaluation.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }
    @Test void candidateCountAndContextBudgetRemainBoundedWithLongStoredContent() throws Exception {
        String concept="budget"+UUID.randomUUID().toString().replace("-","");long category=categories.save(null,"Budget "+UUID.randomUUID(),0).getId();
        var doc=upload(concept,concept,false,category);
        jdbc.update("UPDATE kb_document_chunk SET content=? WHERE version_id=?",concept+" "+"x".repeat(7000),doc.latestVersion().id());
        for(int index=2;index<9;index++)jdbc.update("INSERT INTO kb_document_chunk(kb_id,document_id,version_id,chunk_index,content) VALUES(0,?,?,?,?)",doc.id(),doc.latestVersion().id(),index,concept+" "+"x".repeat(2000));
        var found=retrieval.retrieve(concept);assertEquals(1,found.size());assertEquals(6000,found.getFirst().content().codePointCount(0,found.getFirst().content().length()));
        jdbc.update("UPDATE kb_document_chunk SET content=? WHERE version_id=?",concept,doc.latestVersion().id());assertEquals(5,retrieval.retrieve(concept).size());
    }
}
