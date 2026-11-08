package com.fast.knowledge.integration;

import com.fasterxml.jackson.databind.*;
import com.fast.knowledge.course.*;
import com.fast.knowledge.mapper.UserMapper;
import com.fast.knowledge.model.entity.KbUser;
import com.fast.knowledge.security.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
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
    "spring.datasource.url=jdbc:h2:mem:course_rag_test_b06_personal;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "course.ingest.enabled=false","knowledge.security.rate-limit-enabled=false","APP_ADMIN_USERNAME=","APP_ADMIN_PASSWORD=",
    "knowledge.llm.base-url=","knowledge.llm.api-key="})
@ActiveProfiles("h2") @Import(CourseQaTest.Configuration.class)
class CoursePersonalTest {
    static final String RUN=UUID.randomUUID().toString();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){p.add("knowledge.jwt.secret",()->RUN+RUN);p.add("knowledge.storage.local.base-dir",()->"target/b06-files-"+RUN);}
    @Autowired CourseQaTest.Stub model;@Autowired TestRestTemplate http;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;
    @Autowired UserMapper users;@Autowired JwtUtil jwt;@Autowired CourseIngestWorker worker;
    String admin,student,other,concept;long category,document,success,refusal,failure;
    @BeforeEach void setup() throws Exception {
        concept="personal"+UUID.randomUUID().toString().replace("-","");admin=token("ADMIN");student=token("USER");other=token("USER");
        category=data(call(HttpMethod.POST,"/categories",Map.of("name",UUID.randomUUID().toString()),admin),201,0).path("id").asLong();
        document=upload(concept+" specifies observable behavior.");answerNormally();success=ask(student);
        refusal=data(call(HttpMethod.POST,"/qa",Map.of("question","absent"+UUID.randomUUID().toString().replace("-","")),student),200,0).path("qaId").asLong();
        model.action=(q,e)->{throw new CourseModelGateway.Failure("MODEL_TIMEOUT");};failure=ask(student);answerNormally();
    }
    void answerNormally(){model.action=(q,e)->"{\"status\":\"ANSWER\",\"answer\":\"Observable behavior [S1]\",\"citationIds\":[\"S1\"]}";}
    String token(String role){var u=new KbUser();u.setUsername(UUID.randomUUID().toString().replace("-","").substring(0,24));u.setPassword("unused");u.setRole(role);u.setStatus(1);u.setDisplayName("Fixture");u.setAuthSource("LOCAL");u.setMustChangePassword(0);users.insert(u);return jwt.createToken(u.getId(),u.getUsername(),role);}
    HttpHeaders headers(String token){var h=new HttpHeaders();if(token!=null)h.setBearerAuth(token);return h;}
    ResponseEntity<String> call(HttpMethod method,String path,Object body,String token){return http.exchange("/api"+path,method,new HttpEntity<>(body,headers(token)),String.class);}
    JsonNode data(ResponseEntity<String> response,int status,int code) throws Exception {
        assertEquals(status,response.getStatusCode().value(),response.getBody());var envelope=json.readTree(response.getBody());assertEquals(code,envelope.path("code").asInt());
        assertEquals(response.getHeaders().getFirst("X-Request-Id"),envelope.path("requestId").asText());return envelope.path("data");
    }
    long ask(String token) throws Exception{return data(call(HttpMethod.POST,"/qa",Map.of("question",concept),token),200,0).path("qaId").asLong();}
    long upload(String content) throws Exception {
        var form=new LinkedMultiValueMap<String,Object>();form.add("title",concept);form.add("categoryId",category);
        form.add("file",new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)){@Override public String getFilename(){return "course.txt";}});
        var h=headers(admin);h.setContentType(MediaType.MULTIPART_FORM_DATA);var doc=data(http.postForEntity("/api/documents",new HttpEntity<>(form,h),String.class),202,0);
        worker.run(jdbc.queryForObject("SELECT id FROM kb_index_task WHERE version_id=?",Long.class,doc.path("latestVersion").path("id").asLong())).get(15,TimeUnit.SECONDS);return doc.path("id").asLong();
    }
    JsonNode feedback(long qa,String rating,String reason,String token) throws Exception{return data(call(HttpMethod.POST,"/qa/"+qa+"/feedback",Map.of("rating",rating,"reason",reason),token),200,0);}
    @Test void everyPersonalOperationUsesAuthenticatedOwnerEvenForAdmin() throws Exception {
        for(String token:List.of(other,admin)){
            data(call(HttpMethod.GET,"/qa/"+success,null,token),404,40401);
            data(call(HttpMethod.PUT,"/qa/"+success+"/favorite",null,token),404,40401);
            data(call(HttpMethod.DELETE,"/qa/"+success+"/favorite",null,token),404,40401);
            data(call(HttpMethod.POST,"/qa/"+success+"/feedback",Map.of("rating","HELPFUL"),token),404,40401);
            assertEquals(0,data(call(HttpMethod.GET,"/favorites",null,token),200,0).path("total").asInt());
        }
        var detail=data(call(HttpMethod.GET,"/qa/"+success,null,student),200,0);long source=detail.path("sources").get(0).path("sourceId").asLong();
        data(call(HttpMethod.GET,"/qa/"+success+"/sources/"+source,null,other),404,40401);
        long another=ask(student);data(call(HttpMethod.GET,"/qa/"+another+"/sources/"+source,null,student),404,40401);
        data(call(HttpMethod.GET,"/favorites?userId=1",null,student),422,42201);
    }
    @Test void favoriteIsIdempotentAndReturnedStateAndPaginationAreCurrent() throws Exception {
        for(int i=0;i<2;i++)assertTrue(data(call(HttpMethod.PUT,"/qa/"+success+"/favorite",null,student),200,0).path("favorited").asBoolean());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM kb_favorite WHERE qa_id=?",Integer.class,success));
        assertTrue(data(call(HttpMethod.GET,"/qa/"+success,null,student),200,0).path("favorited").asBoolean());
        for(int i=0;i<10;i++){long qa=ask(student);data(call(HttpMethod.PUT,"/qa/"+qa+"/favorite",null,student),200,0);}
        var page=data(call(HttpMethod.GET,"/favorites",null,student),200,0);assertEquals(11,page.path("total").asInt());assertEquals(10,page.path("items").size());
        for(var item:page.path("items"))assertTrue(item.path("favorited").asBoolean());
        var beyond=data(call(HttpMethod.GET,"/favorites?page=99",null,student),200,0);assertEquals(11,beyond.path("total").asInt());assertTrue(beyond.path("items").isEmpty());
        var history=data(call(HttpMethod.GET,"/qa/history?status=SUCCESS&pageSize=50",null,student),200,0);for(var item:history.path("items"))assertTrue(item.path("favorited").asBoolean());
        for(int i=0;i<2;i++)assertFalse(data(call(HttpMethod.DELETE,"/qa/"+success+"/favorite",null,student),200,0).path("favorited").asBoolean());
        assertFalse(data(call(HttpMethod.GET,"/qa/"+success,null,student),200,0).path("favorited").asBoolean());
        data(call(HttpMethod.PUT,"/qa/"+success+"/favorite",Map.of("owner",1),student),422,42201);
        data(call(HttpMethod.GET,"/favorites?pageSize=51",null,student),422,42201);
    }
    @Test void concurrentFavoriteAndCancellationCannotCreateDuplicates() throws Exception {
        var pool=Executors.newFixedThreadPool(4);try{
            for(HttpMethod method:List.of(HttpMethod.PUT,HttpMethod.DELETE)){
                var gate=new CountDownLatch(1);var futures=new ArrayList<Future<Integer>>();
                for(int i=0;i<4;i++)futures.add(pool.submit(()->{gate.await();return call(method,"/qa/"+success+"/favorite",null,student).getStatusCode().value();}));
                gate.countDown();for(var future:futures)assertEquals(200,future.get(10,TimeUnit.SECONDS));
                assertEquals(method==HttpMethod.PUT?1:0,jdbc.queryForObject("SELECT COUNT(*) FROM kb_favorite WHERE qa_id=?",Integer.class,success));
            }
        }finally{pool.shutdownNow();}
    }
    @Test void feedbackUpsertPreservesIdAndValidatesUnicodeAndWritableFields() throws Exception {
        var first=data(call(HttpMethod.POST,"/qa/"+success+"/feedback",Map.of("rating","HELPFUL"),student),200,0);assertEquals("",first.path("reason").asText());
        var changed=feedback(success,"UNHELPFUL","😀".repeat(500),student);assertEquals(first.path("id"),changed.path("id"));assertEquals("UNHELPFUL",changed.path("rating").asText());
        assertTrue(changed.path("updatedAt").asText().endsWith("+08:00"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM kb_feedback WHERE qa_id=?",Integer.class,success));
        assertEquals(changed,data(call(HttpMethod.GET,"/qa/"+success,null,student),200,0).path("feedback"));
        for(Object invalid:List.of(Map.of("rating","UNKNOWN"),Map.of("rating",1),Map.of("rating","HELPFUL","reason",123),Map.of("rating","HELPFUL","reason","😀".repeat(501)),Map.of("rating","HELPFUL","userId",1)))
            data(call(HttpMethod.POST,"/qa/"+success+"/feedback",invalid,student),422,42201);
        var explicitNull=new HashMap<String,Object>(Map.of("rating","HELPFUL"));explicitNull.put("reason",null);
        data(call(HttpMethod.POST,"/qa/"+success+"/feedback",explicitNull,student),422,42201);
    }
    @Test void concurrentFeedbackMaintainsOneCurrentRowAndOneStableId() throws Exception {
        var pool=Executors.newFixedThreadPool(4);var gate=new CountDownLatch(1);try{
            var futures=new ArrayList<Future<Long>>();
            for(int i=0;i<4;i++){final int index=i;futures.add(pool.submit(()->{gate.await();return feedback(success,index%2==0?"HELPFUL":"UNHELPFUL","round "+index,student).path("id").asLong();}));}
            gate.countDown();var ids=new HashSet<Long>();for(var future:futures)ids.add(future.get(10,TimeUnit.SECONDS));assertEquals(1,ids.size());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM kb_feedback WHERE qa_id=?",Integer.class,success));
        }finally{pool.shutdownNow();}
    }
    @Test void refusalAndFailureCannotBeFavoritedOrRated() throws Exception {
        for(long qa:List.of(refusal,failure)){
            data(call(HttpMethod.PUT,"/qa/"+qa+"/favorite",null,student),422,42201);
            data(call(HttpMethod.POST,"/qa/"+qa+"/feedback",Map.of("rating","HELPFUL"),student),422,42201);
            data(call(HttpMethod.DELETE,"/qa/"+qa+"/favorite",null,student),200,0);
        }
        data(call(HttpMethod.PUT,"/qa/0/favorite",null,student),422,42201);
        data(call(HttpMethod.POST,"/qa/9007199254740991/feedback",Map.of("rating","HELPFUL"),student),404,40401);
    }
    @Test void adminFeedbackFiltersByUpdatedTimeAndReturnsOnlyCurrentRows() throws Exception {
        long b=ask(other),c=ask(student),d=ask(student);
        var aFeedback=feedback(success,"HELPFUL","first",student);var bFeedback=feedback(b,"UNHELPFUL","second",other);
        var cFeedback=feedback(c,"HELPFUL","third",student);var dFeedback=feedback(d,"HELPFUL","fourth",student);
        var values=List.of(new Object[]{aFeedback.path("id").asLong(),"2026-10-26 23:59:59.999999"},new Object[]{bFeedback.path("id").asLong(),"2026-10-27 00:00:00"},
                new Object[]{cFeedback.path("id").asLong(),"2026-10-27 23:59:59.999999"},new Object[]{dFeedback.path("id").asLong(),"2026-10-28 00:00:00"});
        for(Object[] item:values)jdbc.update("UPDATE kb_feedback SET created_at=TIMESTAMP '2020-01-01 00:00:00',updated_at=CAST(? AS TIMESTAMP) WHERE id=?",item[1],item[0]);
        String range="from=2026-10-27&to=2026-10-27";
        var list=data(call(HttpMethod.GET,"/admin/feedback?"+range,null,admin),200,0);assertEquals(2,list.path("total").asInt());
        assertEquals(cFeedback.path("id"),list.path("items").get(0).path("id"));
        for(var row:list.path("items")){assertTrue(row.has("username"));assertTrue(row.has("userId"));assertEquals(concept,row.path("question").asText());assertFalse(row.has("password"));}
        assertEquals(1,data(call(HttpMethod.GET,"/admin/feedback?"+range+"&rating=UNHELPFUL",null,admin),200,0).path("total").asInt());
        var beyond=data(call(HttpMethod.GET,"/admin/feedback?"+range+"&page=99",null,admin),200,0);assertEquals(2,beyond.path("total").asInt());assertTrue(beyond.path("items").isEmpty());
        data(call(HttpMethod.GET,"/admin/feedback",null,student),403,40301);data(call(HttpMethod.GET,"/admin/stats",null,student),403,40301);
        data(call(HttpMethod.GET,"/admin/stats",null,null),401,40101);
        for(String query:List.of("from=2026-02-30","from=2026-10-28&to=2026-10-27","from=2026-1-01","to=invalid","owner=1"))
            data(call(HttpMethod.GET,"/admin/stats?"+query,null,admin),422,42201);
        data(call(HttpMethod.GET,"/admin/feedback?rating=UNKNOWN",null,admin),422,42201);
        data(call(HttpMethod.GET,"/admin/feedback?pageSize=51",null,admin),422,42201);
    }
    @Test void statisticsUseIndependentDateColumnsInclusiveDayAndAllBusinessStates() throws Exception {
        long second=ask(other),failedDocument=upload(" "),outsideDocument=upload("outside original");
        var a=feedback(success,"HELPFUL","current",student);var b=feedback(second,"UNHELPFUL","current",other);
        feedback(success,"UNHELPFUL","changed once",student);feedback(success,"HELPFUL","changed twice",student);
        data(call(HttpMethod.DELETE,"/documents/"+document,null,admin),200,0);
        jdbc.update("UPDATE kb_document SET created_at=TIMESTAMP '2022-06-10 00:00:00' WHERE id=?",document);
        jdbc.update("UPDATE kb_document SET created_at=TIMESTAMP '2022-06-10 23:59:59.999999' WHERE id=?",failedDocument);
        jdbc.update("UPDATE kb_document SET created_at=TIMESTAMP '2022-06-09 23:59:59.999999' WHERE id=?",outsideDocument);
        jdbc.update("UPDATE kb_user SET created_at=TIMESTAMP '2022-06-10 00:00:00' WHERE id=?",jwt.getUserId(student));
        jdbc.update("UPDATE kb_user SET created_at=TIMESTAMP '2022-06-10 23:59:59.999999',status=0 WHERE id=?",jwt.getUserId(other));
        jdbc.update("UPDATE kb_qa_record SET created_at=TIMESTAMP '2022-06-10 00:00:00' WHERE id=?",success);
        jdbc.update("UPDATE kb_qa_record SET created_at=TIMESTAMP '2022-06-10 23:59:59.999999' WHERE id=?",refusal);
        jdbc.update("UPDATE kb_qa_record SET created_at=TIMESTAMP '2022-06-10 12:00:00' WHERE id=?",failure);
        jdbc.update("UPDATE kb_qa_record SET created_at=TIMESTAMP '2022-06-09 23:59:59.999999' WHERE id=?",second);
        jdbc.update("UPDATE kb_feedback SET created_at=TIMESTAMP '2020-01-01 00:00:00',updated_at=TIMESTAMP '2022-06-10 00:00:00' WHERE id=?",a.path("id").asLong());
        jdbc.update("UPDATE kb_feedback SET updated_at=TIMESTAMP '2022-06-10 23:59:59.999999' WHERE id=?",b.path("id").asLong());
        var stats=data(call(HttpMethod.GET,"/admin/stats?from=2022-06-10&to=2022-06-10",null,admin),200,0);
        assertEquals(2,stats.path("documentCount").asLong());assertEquals(2,stats.path("userCount").asLong());
        for(String name:List.of("qaSuccessCount","qaNoEvidenceCount","qaFailedCount","feedbackHelpfulCount","feedbackUnhelpfulCount"))assertEquals(1,stats.path(name).asLong(),name);
        assertEquals("2022-06-10",stats.path("from").asText());assertEquals("2022-06-10",stats.path("to").asText());
        var earlier=data(call(HttpMethod.GET,"/admin/stats?to=2022-06-09",null,admin),200,0);assertEquals(1,earlier.path("qaSuccessCount").asLong());assertEquals(0,earlier.path("feedbackHelpfulCount").asLong());
        var empty=data(call(HttpMethod.GET,"/admin/stats?from=2100-01-01",null,admin),200,0);assertEquals(0,empty.path("documentCount").asLong());assertEquals(0,empty.path("qaSuccessCount").asLong());
        var all=data(call(HttpMethod.GET,"/admin/stats",null,admin),200,0);assertTrue(all.path("from").isNull());assertTrue(all.path("to").isNull());assertTrue(all.path("userCount").asLong()>=3);
    }
}
