package com.fast.knowledge.integration;

import com.fasterxml.jackson.databind.*;
import com.fast.knowledge.course.*;
import com.fast.knowledge.mapper.UserMapper;
import com.fast.knowledge.model.entity.KbUser;
import com.fast.knowledge.security.JwtUtil;
import com.fast.knowledge.storage.StorageProvider;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.util.LinkedMultiValueMap;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:course_rag_test_b04_versions;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "course.ingest.enabled=false","knowledge.security.rate-limit-enabled=false","APP_ADMIN_USERNAME=","APP_ADMIN_PASSWORD=",
    "knowledge.llm.base-url=","knowledge.llm.api-key="})
@ActiveProfiles("h2")
class CourseVersionTest {
    static final String RUN=UUID.randomUUID().toString();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("knowledge.jwt.secret",()->RUN+RUN);p.add("knowledge.storage.local.base-dir",()->"target/b04-files-"+RUN);
    }
    @Autowired TestRestTemplate http;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;
    @Autowired UserMapper users;@Autowired JwtUtil jwt;@Autowired CourseIngestWorker worker;@Autowired CourseIngestTasks tasks;
    @Autowired StorageProvider storage;
    String admin,student;long category;String title;
    @BeforeEach void fixtures() throws Exception {
        title="Version_"+UUID.randomUUID();admin=token("ADMIN");student=token("USER");
        category=data(call(HttpMethod.POST,"/categories",Map.of("name",UUID.randomUUID().toString()),admin),201,0).path("id").asLong();
    }
    String token(String role) {
        var user=new KbUser();user.setUsername(UUID.randomUUID().toString().replace("-","").substring(0,24));user.setPassword("unused");
        user.setRole(role);user.setStatus(1);user.setDisplayName("Fixture");user.setAuthSource("LOCAL");user.setMustChangePassword(0);users.insert(user);
        return jwt.createToken(user.getId(),user.getUsername(),role);
    }
    HttpHeaders headers(String token) {var h=new HttpHeaders();h.setBearerAuth(token);return h;}
    ResponseEntity<String> call(HttpMethod method,String path,Object body,String token) {return http.exchange("/api"+path,method,new HttpEntity<>(body,headers(token)),String.class);}
    JsonNode data(ResponseEntity<String> response,int status,int code) throws Exception {
        assertEquals(status,response.getStatusCode().value(),response.getBody());var envelope=json.readTree(response.getBody());
        assertEquals(code,envelope.path("code").asInt());return envelope.path("data");
    }
    ResponseEntity<String> file(Long id,String content,String token) {
        return file(id,"version.txt",content.getBytes(StandardCharsets.UTF_8),token);
    }
    ResponseEntity<String> file(Long id,String name,byte[] bytes,String token) {
        var form=new LinkedMultiValueMap<String,Object>();
        if(id==null){form.add("title",title);form.add("categoryId",category);}
        form.add("file",new ByteArrayResource(bytes){@Override public String getFilename(){return name;}});
        var h=headers(token);h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange("/api/documents"+(id==null?"":"/"+id+"/file"),id==null?HttpMethod.POST:HttpMethod.PUT,new HttpEntity<>(form,h),String.class);
    }
    JsonNode upload(String content) throws Exception {return data(file(null,content,admin),202,0);}
    JsonNode detail(long id) throws Exception {return data(call(HttpMethod.GET,"/documents/"+id,null,admin),200,0);}
    void parse(JsonNode result) throws Exception {
        long version=result.path("latestVersion").path("id").asLong();long task=jdbc.queryForObject("SELECT id FROM kb_index_task WHERE version_id=?",Long.class,version);
        worker.run(task).get(15,TimeUnit.SECONDS);
    }
    String download(long id) {
        var r=http.exchange("/api/documents/"+id+"/download",HttpMethod.GET,new HttpEntity<>(headers(student)),byte[].class);
        assertEquals(200,r.getStatusCode().value());return new String(r.getBody(),StandardCharsets.UTF_8);
    }
    @Test void replacementKeepsOldReadableUntilAtomicSuccessAndRetainsItsOriginal() throws Exception {
        var first=upload("oldNeedle original");parse(first);long id=first.path("id").asLong(),old=first.path("latestVersion").path("id").asLong();
        String key=jdbc.queryForObject("SELECT object_key FROM kb_document_version WHERE id=?",String.class,old);
        var next=data(file(id,"newNeedle original",admin),202,0);
        assertEquals(2,next.path("latestVersion").path("versionNumber").asInt());assertEquals(old,next.path("currentVersion").path("id").asLong());
        assertEquals("oldNeedle original",download(id));
        assertEquals(1,data(call(HttpMethod.GET,"/documents?categoryId="+category+"&keyword=oldNeedle",null,student),200,0).path("total").asInt());
        data(file(id,"duplicate",admin),409,40904);parse(next);
        assertEquals("newNeedle original",download(id));assertEquals(0,data(call(HttpMethod.GET,"/documents?categoryId="+category+"&keyword=oldNeedle",null,student),200,0).path("total").asInt());
        assertEquals("READY",jdbc.queryForObject("SELECT status FROM kb_document_version WHERE id=?",String.class,old));
        assertEquals("oldNeedle original",new String(storage.openInputStream(key).readAllBytes(),StandardCharsets.UTF_8));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM kb_document_chunk WHERE version_id=?",Integer.class,old));
    }
    @Test void officeReplacementFailureKeepsOldVersionThenSuccessSwitchesFormats() throws Exception {
        var original=upload("oldNeedle original");parse(original);long id=original.path("id").asLong();
        var empty=data(file(id,"empty.docx",CourseFixtures.docx(""),admin),202,0);parse(empty);
        assertEquals("oldNeedle original",download(id));assertEquals("FAILED",detail(id).path("latestVersion").path("status").asText());
        for(String type:List.of("DOCX","PPTX")) {
            byte[] bytes=type.equals("DOCX")?CourseFixtures.docx("新需求分析"):CourseFixtures.pptx("新接口测试");
            var next=data(file(id,"next."+type.toLowerCase(Locale.ROOT),bytes,admin),202,0);parse(next);
            assertEquals(type,detail(id).path("currentVersion").path("fileType").asText());
            var r=http.exchange("/api/documents/"+id+"/download",HttpMethod.GET,new HttpEntity<>(headers(student)),byte[].class);
            assertArrayEquals(bytes,r.getBody());
        }
    }
    @Test void failedReplacementPreservesCurrentAndFailedInitialUploadCanBeRepaired() throws Exception {
        var first=upload("old original");parse(first);long id=first.path("id").asLong();
        var bad=data(file(id," \n\t",admin),202,0);parse(bad);
        var state=detail(id);assertEquals("FAILED",state.path("latestVersion").path("status").asText());
        assertEquals(first.path("latestVersion").path("id"),state.path("currentVersion").path("id"));assertEquals("old original",download(id));
        var good=data(file(id,"third original",admin),202,0);assertEquals(3,good.path("latestVersion").path("versionNumber").asInt());parse(good);
        assertEquals("third original",download(id));
        var initial=upload(" ");parse(initial);assertTrue(detail(initial.path("id").asLong()).path("currentVersion").isNull());
        var repaired=data(file(initial.path("id").asLong(),"repaired original",admin),202,0);parse(repaired);
        assertEquals("repaired original",download(initial.path("id").asLong()));
    }
    @Test void concurrentReplacementAcceptsOnlyOneCandidate() throws Exception {
        var first=upload("old original");parse(first);long id=first.path("id").asLong();
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            Callable<Integer> action=()->{gate.await();return file(id,"candidate",admin).getStatusCode().value();};
            var a=pool.submit(action);var b=pool.submit(action);gate.countDown();var statuses=new ArrayList<>(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)));Collections.sort(statuses);
            assertEquals(List.of(202,409),statuses);assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM kb_document_version WHERE document_id=?",Integer.class,id));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM kb_document_version WHERE document_id=? AND status='QUEUED'",Integer.class,id));
        } finally{pool.shutdownNow();}
    }
    @Test void offlineDuringParsingNeverRestoresVisibilityAndDeletionIsIdempotent() throws Exception {
        var first=upload("old original");parse(first);long id=first.path("id").asLong();var next=data(file(id,"new original",admin),202,0);
        data(call(HttpMethod.DELETE,"/documents/"+id,null,admin),200,0);parse(next);data(call(HttpMethod.DELETE,"/documents/"+id,null,admin),200,0);
        var state=detail(id);assertEquals("OFFLINE",state.path("status").asText());assertEquals(2,state.path("currentVersion").path("versionNumber").asInt());
        for(String suffix:List.of("","/download","/chunks","/status"))data(call(HttpMethod.GET,"/documents/"+id+suffix,null,student),404,40401);
        data(call(HttpMethod.GET,"/documents/"+id+"/download",null,admin),404,40401);
        data(file(id,"cannot restore",admin),409,40906);data(call(HttpMethod.PATCH,"/documents/"+id,Map.of("title","restore","categoryId",category),admin),409,40906);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM kb_document_version WHERE document_id=?",Integer.class,id));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM kb_document_chunk WHERE document_id=?",Integer.class,id));
        data(call(HttpMethod.DELETE,"/categories/"+category,null,admin),409,40903);
    }
    @Test void metadataChangesOnlyLiveFieldsAndPreservesExistingHistorySnapshot() throws Exception {
        var first=upload("original");parse(first);long id=first.path("id").asLong();
        String snapshot="[{\"documentTitle\":\""+title+"\",\"documentId\":"+id+"}]";
        jdbc.update("INSERT INTO kb_qa_history(kb_id,question,answer,sources) VALUES (0,?,?,?)",title,"historical answer",snapshot);
        long other=data(call(HttpMethod.POST,"/categories",Map.of("name",UUID.randomUUID().toString()),admin),201,0).path("id").asLong();
        var changed=data(call(HttpMethod.PATCH,"/documents/"+id,Map.of("title","  new title  ","categoryId",other),admin),200,0);
        assertEquals("new title",changed.path("title").asText());assertEquals(other,changed.path("categoryId").asLong());
        assertEquals(snapshot,jdbc.queryForObject("SELECT sources FROM kb_qa_history WHERE question=?",String.class,title));
        assertEquals("original",download(id));data(call(HttpMethod.DELETE,"/categories/"+category,null,admin),200,0);
        for(Object invalid:List.of(Map.of("title","x"),Map.of("title",123,"categoryId",other),Map.of("title","x","categoryId",1.5),Map.of("title","x","categoryId",other,"owner",1)))
            data(call(HttpMethod.PATCH,"/documents/"+id,invalid,admin),422,42201);
        data(call(HttpMethod.PATCH,"/documents/"+id,Map.of("title","x","categoryId",other),student),403,40301);
        data(file(id,"blocked",student),403,40301);data(call(HttpMethod.DELETE,"/documents/"+id,null,student),403,40301);
    }
    @Test void replacementRacingWithOfflineSerializesAndCannotReactivateDocument() throws Exception {
        var first=upload("old original");parse(first);long id=first.path("id").asLong();
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var replacement=pool.submit(()->{gate.await();return file(id,"new original",admin);});
            var offline=pool.submit(()->{gate.await();return call(HttpMethod.DELETE,"/documents/"+id,null,admin);});
            gate.countDown();var result=replacement.get(10,TimeUnit.SECONDS);data(offline.get(10,TimeUnit.SECONDS),200,0);
            if(result.getStatusCode().value()==202) parse(data(result,202,0));else data(result,409,40906);
            assertEquals("OFFLINE",detail(id).path("status").asText());
            data(call(HttpMethod.GET,"/documents/"+id+"/download",null,student),404,40401);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM kb_document_chunk WHERE version_id=?",Integer.class,first.path("latestVersion").path("id").asLong()));
        } finally {pool.shutdownNow();}
    }
    @Test void obsoleteCandidateCannotOverrideNewerVersionAndRetriesDoNotDuplicateChunks() throws Exception {
        var first=upload("old original");parse(first);long id=first.path("id").asLong();var next=data(file(id,"candidate",admin),202,0);
        long version=next.path("latestVersion").path("id").asLong(),task=jdbc.queryForObject("SELECT id FROM kb_index_task WHERE version_id=?",Long.class,version);
        var claim=tasks.claim(task);tasks.append(claim,List.of(new CourseFileParser.Chunk(1,null,"candidate")));
        jdbc.update("INSERT INTO kb_document_version(document_id,version_number,status,object_key,original_filename,file_type,file_size) VALUES (?,3,'FAILED',?,'new.txt','TXT',1)",id,"fixture/"+UUID.randomUUID());
        assertFalse(tasks.complete(claim,1));assertEquals("old original",download(id));
        assertEquals("READY",jdbc.queryForObject("SELECT status FROM kb_document_version WHERE id=?",String.class,first.path("latestVersion").path("id").asLong()));
        worker.run(task).get(10,TimeUnit.SECONDS);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM kb_document_chunk WHERE version_id=?",Integer.class,version));
    }
}
