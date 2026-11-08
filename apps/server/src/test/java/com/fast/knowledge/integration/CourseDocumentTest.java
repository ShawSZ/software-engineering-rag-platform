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
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:course_rag_test_b03_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "knowledge.security.rate-limit-enabled=false","course.ingest.poll-ms=100","APP_ADMIN_USERNAME=","APP_ADMIN_PASSWORD=",
    "knowledge.llm.base-url=","knowledge.llm.api-key="})
@ActiveProfiles("h2")
class CourseDocumentTest {
    static final String RUN=UUID.randomUUID().toString(),SECRET=RUN+UUID.randomUUID();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("knowledge.jwt.secret",()->SECRET);p.add("knowledge.storage.local.base-dir",()->"target/b03-http-"+RUN);
    }
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired UserMapper users;
    @Autowired JwtUtil jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired StorageProvider storage;
    String admin,student,prefix;long category;
    @BeforeEach void fixtures() throws Exception {
        prefix="b03_"+UUID.randomUUID().toString().substring(0,8);
        admin=token("ADMIN");student=token("USER");
        category=body(call(HttpMethod.POST,"/categories",Map.of("name",prefix),admin),201,0).path("id").asLong();
    }
    String token(String role) {
        KbUser user=new KbUser();user.setUsername("u"+UUID.randomUUID().toString().replace("-","").substring(0,20));
        user.setPassword("unused-no-password-login");user.setRole(role);user.setStatus(1);user.setDisplayName("Fixture");
        user.setAuthSource("LOCAL");user.setMustChangePassword(0);users.insert(user);
        return jwt.createToken(user.getId(),user.getUsername(),role);
    }
    HttpHeaders headers(String token) {var h=new HttpHeaders();if(token!=null) h.setBearerAuth(token);return h;}
    ResponseEntity<String> call(HttpMethod method,String path,Object body,String token) {
        return http.exchange("/api"+path,method,new HttpEntity<>(body,headers(token)),String.class);
    }
    JsonNode body(ResponseEntity<String> response,int status,int code) throws Exception {
        assertEquals(status,response.getStatusCode().value(),response.getBody());var data=json.readTree(response.getBody());
        assertEquals(code,data.path("code").asInt());assertEquals(response.getHeaders().getFirst("X-Request-Id"),data.path("requestId").asText());
        if(code!=0) assertTrue(data.path("data").isNull());return data.path("data");
    }
    ResponseEntity<String> upload(String name,String mime,byte[] bytes,String title,long categoryId,String token) {
        var form=new LinkedMultiValueMap<String,Object>();form.add("title",title);form.add("categoryId",categoryId);
        var partHeaders=new HttpHeaders();partHeaders.setContentType(MediaType.parseMediaType(mime));
        form.add("file",new HttpEntity<>(new ByteArrayResource(bytes){@Override public String getFilename(){return name;}},partHeaders));
        var h=headers(token);h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.postForEntity("/api/documents",new HttpEntity<>(form,h),String.class);
    }
    JsonNode accepted(String name,String mime,byte[] bytes,String title) throws Exception {
        var result=body(upload(name,mime,bytes,title,category,admin),202,0);
        assertEquals("QUEUED",result.path("latestVersion").path("status").asText());assertTrue(result.path("currentVersion").isNull());
        return result;
    }
    JsonNode terminal(long id,String expected) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline) {
            var value=body(call(HttpMethod.GET,"/documents/"+id+"/status",null,admin),200,0);
            String state=value.path("latestVersion").path("status").asText();
            if(Set.of("READY","FAILED").contains(state)) {assertEquals(expected,state,value.toString());return value;}
            Thread.sleep(30);
        }
        fail("Parser did not reach terminal state");return null;
    }
    @Test void categoryCrudOrderingNormalizationAndStrictContract() throws Exception {
        var unicode=body(call(HttpMethod.POST,"/categories",Map.of("name","😀".repeat(50)),admin),201,0);
        assertEquals(50,unicode.path("name").asText().codePointCount(0,unicode.path("name").asText().length()));
        body(call(HttpMethod.DELETE,"/categories/"+unicode.path("id").asLong(),null,admin),200,0);
        var a=body(call(HttpMethod.POST,"/categories",Map.of("name","  "+prefix+"_A  ","sortOrder",12),admin),201,0);
        long id=a.path("id").asLong();assertEquals(prefix+"_A",a.path("name").asText());
        body(call(HttpMethod.POST,"/categories",Map.of("name",prefix+"_A"),admin),409,40902);
        body(call(HttpMethod.PATCH,"/categories/"+id,Map.of("name",prefix+"_B","sortOrder",1),admin),200,0);
        var list=body(call(HttpMethod.GET,"/categories",null,student),200,0);
        int previous=-1;long previousId=0;
        for(var c:list) {int order=c.path("sortOrder").asInt();long current=c.path("id").asLong();
            assertTrue(order>previous || order==previous && current>previousId);previous=order;previousId=current;}
        for(Object order:List.of(-1,1.5,"1",true))
            body(call(HttpMethod.POST,"/categories",Map.of("name",prefix+"_bad","sortOrder",order),admin),422,42201);
        for(var invalid:List.of(Map.of("name"," "),Map.of("name","名".repeat(51)),Map.of("name",123),Map.of("name",prefix,"owner",1)))
            body(call(HttpMethod.POST,"/categories",invalid,admin),422,42201);
        body(call(HttpMethod.PATCH,"/categories/"+id,Map.of("name",prefix),admin),422,42201);
        body(call(HttpMethod.GET,"/categories?owner=1",null,student),422,42201);
        body(call(HttpMethod.PATCH,"/categories/0",Map.of("name",prefix,"sortOrder",0),admin),422,42201);
        body(call(HttpMethod.PATCH,"/categories/9007199254740991",Map.of("name",prefix,"sortOrder",0),admin),404,40401);
        body(call(HttpMethod.PATCH,"/categories/"+id,Map.of("name",prefix,"sortOrder",0),student),403,40301);
        body(call(HttpMethod.DELETE,"/categories/"+id,null,student),403,40301);
        body(call(HttpMethod.DELETE,"/categories/"+id,null,admin),200,0);
        body(call(HttpMethod.DELETE,"/categories/"+id,null,admin),404,40401);
    }
    @Test void officeUploadParseLocationsSearchAndOriginalDownload() throws Exception {
        var fixtures=new LinkedHashMap<String,byte[]>();
        fixtures.put("DOCX",CourseFixtures.docx("需求分析确定系统需要做什么。需求评审通过检查需求的一致性、完整性和可验证性发现问题。"));
        fixtures.put("PPTX",CourseFixtures.pptx("单元测试检查独立模块的行为。", "集成测试检查模块之间的接口与协作。"));
        var folder=java.nio.file.Path.of("target/office-fixtures");java.nio.file.Files.createDirectories(folder);
        for(var fixture:fixtures.entrySet()) {
            String type=fixture.getKey(),name="course."+type.toLowerCase(Locale.ROOT);byte[] bytes=fixture.getValue();
            java.nio.file.Files.write(folder.resolve(name),bytes);
            long id=accepted(name,CourseFileParser.mime(type),bytes,prefix+" "+type).path("id").asLong();
            var done=terminal(id,"READY");assertEquals(type,done.path("currentVersion").path("fileType").asText());
            var chunks=body(call(HttpMethod.GET,"/documents/"+id+"/chunks",null,student),200,0).path("items");
            assertFalse(chunks.isEmpty());assertTrue(chunks.toString().contains(type.equals("DOCX")?"需求评审":"集成测试"));
            if(type.equals("DOCX")) assertTrue(chunks.get(0).path("pageNo").isNull());
            else {assertEquals(1,chunks.get(0).path("pageNo").asInt());assertEquals(2,chunks.get(1).path("pageNo").asInt());}
            var downloaded=http.exchange("/api/documents/"+id+"/download",HttpMethod.GET,new HttpEntity<>(headers(student)),byte[].class);
            assertEquals(200,downloaded.getStatusCode().value());assertArrayEquals(bytes,downloaded.getBody());
            assertEquals(CourseFileParser.mime(type),downloaded.getHeaders().getContentType().toString());
            var found=body(call(HttpMethod.GET,"/documents?categoryId="+category+"&keyword="+(type.equals("DOCX")?"需求评审":"集成测试"),null,student),200,0);
            assertEquals(1,found.path("total").asInt());assertEquals(id,found.path("items").get(0).path("id").asLong());
        }
        for(String type:List.of("DOCX","PPTX")) {
            byte[] empty=type.equals("DOCX")?CourseFixtures.docx(""):CourseFixtures.pptx("");
            long id=accepted("empty."+type.toLowerCase(Locale.ROOT),CourseFileParser.mime(type),empty,prefix+" empty").path("id").asLong();
            assertEquals("EMPTY_TEXT",terminal(id,"FAILED").path("latestVersion").path("errorCode").asText());
            body(call(HttpMethod.GET,"/documents/"+id+"/download",null,student),404,40401);
        }
    }
    @Test void txtUploadAsyncChunksLocationsAndDownloadHash() throws Exception {
        String source="😀需求分析案例".repeat(250);byte[] bytes=("\uFEFF"+source).getBytes(StandardCharsets.UTF_8);
        var queued=accepted("../../安全讲义.txt","text/plain; charset=UTF-8",bytes,prefix+" TXT");long id=queued.path("id").asLong();
        var done=terminal(id,"READY");long version=done.path("currentVersion").path("id").asLong();
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                jdbc.queryForObject("SELECT sha256 FROM kb_document_version WHERE id=?",String.class,version));
        var chunks=body(call(HttpMethod.GET,"/documents/"+id+"/chunks",null,student),200,0);
        assertEquals(3,chunks.path("total").asInt());
        for(var chunk:chunks.path("items")) {
            int index=chunk.path("chunkIndex").asInt();assertEquals(version,chunk.path("versionId").asLong());assertTrue(chunk.path("pageNo").isNull());
            int start=(index-1)*700,end=Math.min(start+800,source.codePointCount(0,source.length()));
            assertEquals(source.substring(source.offsetByCodePoints(0,start),source.offsetByCodePoints(0,end)),chunk.path("content").asText());
        }
        var beyond=body(call(HttpMethod.GET,"/documents/"+id+"/chunks?page=99",null,student),200,0);
        assertEquals(3,beyond.path("total").asInt());assertTrue(beyond.path("items").isEmpty());
        var single=body(call(HttpMethod.GET,"/documents/"+id+"/chunks?chunkIndex=2",null,student),200,0);
        assertEquals(1,single.path("items").size());assertEquals(2,single.path("items").get(0).path("chunkIndex").asInt());
        var downloaded=http.exchange("/api/documents/"+id+"/download",HttpMethod.GET,new HttpEntity<>(headers(student)),byte[].class);
        assertEquals(200,downloaded.getStatusCode().value());assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes),MessageDigest.getInstance("SHA-256").digest(downloaded.getBody()));
        assertEquals("text/plain;charset=UTF-8",downloaded.getHeaders().getContentType().toString());
        assertFalse(downloaded.getHeaders().getFirst("Content-Disposition").contains("../"));
        assertFalse(queued.toString().contains("objectKey"));assertFalse(queued.toString().contains("filePath"));
        String key=jdbc.queryForObject("SELECT object_key FROM kb_document_version WHERE id=?",String.class,version);
        assertFalse(key.contains(".."));assertFalse(key.contains("安全"));storage.delete(key);
        body(call(HttpMethod.GET,"/documents/"+id+"/download",null,student),404,40402);
    }
    @Test void pdfPagesAndFailedInputsRemainOutsideStudentVisibility() throws Exception {
        byte[] pdf=CourseFixtures.pdf(false,"Requirements page one","Testing page two");
        long id=accepted("lecture.pdf","application/pdf",pdf,prefix+" PDF").path("id").asLong();terminal(id,"READY");
        var chunks=body(call(HttpMethod.GET,"/documents/"+id+"/chunks",null,student),200,0).path("items");
        assertEquals(2,chunks.size());assertEquals(1,chunks.get(0).path("pageNo").asInt());assertEquals(2,chunks.get(1).path("pageNo").asInt());
        assertFalse(chunks.get(0).path("content").asText().contains("page two"));
        var download=http.exchange("/api/documents/"+id+"/download",HttpMethod.GET,new HttpEntity<>(headers(student)),byte[].class);
        assertArrayEquals(pdf,download.getBody());assertEquals(MediaType.APPLICATION_PDF,download.getHeaders().getContentType());
        var invalid=List.of(new Object[]{"blank.txt","text/plain"," \r\n\t".getBytes(),"EMPTY_TEXT"},
                new Object[]{"scan.pdf","application/pdf",CourseFixtures.scannedPdf(),"EMPTY_TEXT"},
                new Object[]{"broken.pdf","application/pdf","%PDF-1.7 broken".getBytes(),"PDF_PARSE_ERROR"},
                new Object[]{"locked.pdf","application/pdf",CourseFixtures.pdf(true,"Protected"),"PDF_PARSE_ERROR"});
        for(Object[] fixture:invalid) {
            long failed=accepted((String)fixture[0],(String)fixture[1],(byte[])fixture[2],prefix+" failure").path("id").asLong();
            var status=terminal(failed,"FAILED");assertEquals(fixture[3],status.path("latestVersion").path("errorCode").asText());
            assertTrue(status.path("currentVersion").isNull());
            for(String suffix:List.of("","/status","/chunks","/download")) body(call(HttpMethod.GET,"/documents/"+failed+suffix,null,student),404,40401);
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM kb_document_chunk WHERE document_id=?",Integer.class,failed));
        }
    }
    @Test void uploadRejectsBadFormatsEncodingSizeAndFieldInjectionSynchronously() throws Exception {
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE category_id=?",Long.class,category);
        body(upload("empty.txt","text/plain",new byte[0],prefix,category,admin),422,42205);
        body(upload("wrong.md","text/plain","text".getBytes(),prefix,category,admin),422,42202);
        body(upload("wrong.pdf","application/pdf","text".getBytes(),prefix,category,admin),422,42202);
        body(upload("wrong.txt","text/html","text".getBytes(),prefix,category,admin),422,42202);
        body(upload("bad.txt","text/plain",new byte[]{(byte)0xff},prefix,category,admin),422,42204);
        body(upload("ok.txt","text/plain","text".getBytes(),prefix,9007199254740991L,admin),404,40401);
        body(upload("ok.txt","text/plain","text".getBytes()," ",category,admin),422,42201);
        body(upload("ok.txt","text/plain","text".getBytes(),"😀".repeat(201),category,admin),422,42201);
        body(upload("ok.txt","text/plain","text".getBytes(),prefix,category,student),403,40301);
        var form=new LinkedMultiValueMap<String,Object>();form.add("title",prefix);form.add("categoryId",category);form.add("owner",1);
        form.add("file",new ByteArrayResource("text".getBytes()){@Override public String getFilename(){return "test.txt";}});
        var h=headers(admin);h.setContentType(MediaType.MULTIPART_FORM_DATA);
        body(http.postForEntity("/api/documents",new HttpEntity<>(form,h),String.class),422,42201);
        form.remove("owner");form.add("file",form.getFirst("file"));
        body(http.postForEntity("/api/documents",new HttpEntity<>(form,h),String.class),422,42201);
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE category_id=?",Long.class,category));
        byte[] limit=new byte[(int)CourseFileParser.MAX_BYTES];Arrays.fill(limit,(byte)' ');limit[0]='A';
        long id=accepted("limit.txt","text/plain",limit,prefix+" limit").path("id").asLong();terminal(id,"READY");
        body(upload("too-large.txt","text/plain",Arrays.copyOf(limit,limit.length+1),prefix,category,admin),422,42203);
    }
    @Test void listCombinesFiltersAndNeverUsesOldOrOfflineContent() throws Exception {
        long a=accepted("a.txt","text/plain","uniqueNeedle".getBytes(),prefix+" target").path("id").asLong();terminal(a,"READY");
        long b=accepted("b.txt","text/plain","different".getBytes(),prefix+" other").path("id").asLong();terminal(b,"READY");
        var filtered=body(call(HttpMethod.GET,"/documents?categoryId="+category+"&title=target&keyword=uniqueNeedle",null,student),200,0);
        assertEquals(1,filtered.path("total").asInt());assertEquals(a,filtered.path("items").get(0).path("id").asLong());
        assertEquals(0,body(call(HttpMethod.GET,"/documents?categoryId="+category+"&title=other&keyword=uniqueNeedle",null,student),200,0).path("total").asInt());
        long otherCategory=body(call(HttpMethod.POST,"/categories",Map.of("name",prefix+"_other"),admin),201,0).path("id").asLong();
        assertEquals(0,body(call(HttpMethod.GET,"/documents?categoryId="+otherCategory+"&keyword=uniqueNeedle",null,student),200,0).path("total").asInt());
        // A retained READY historical version is deliberately seeded; B04 replacement is not invoked here.
        jdbc.update("INSERT INTO kb_document_version(document_id,version_number,status,object_key,original_filename,file_type,file_size) VALUES (?,2,'READY',?,'old.txt','TXT',3)",a,"history/"+UUID.randomUUID()+".txt");
        long historical=jdbc.queryForObject("SELECT id FROM kb_document_version WHERE document_id=? AND version_number=2",Long.class,a);
        jdbc.update("INSERT INTO kb_document_chunk(kb_id,document_id,version_id,chunk_index,content) VALUES (0,?,?,1,'historicalOnlyNeedle')",a,historical);
        assertEquals(0,body(call(HttpMethod.GET,"/documents?categoryId="+category+"&keyword=historicalOnlyNeedle",null,student),200,0).path("total").asInt());
        assertEquals(0,body(call(HttpMethod.GET,"/documents?categoryId="+category+"&keyword=%27%20OR%201%3D1--",null,student),200,0).path("total").asInt());
        var empty=body(call(HttpMethod.GET,"/documents?categoryId="+category+"&page=999",null,student),200,0);
        assertEquals(2,empty.path("total").asInt());assertTrue(empty.path("items").isEmpty());
        jdbc.update("UPDATE kb_document SET enabled=0 WHERE id=?",a);
        assertEquals(1,body(call(HttpMethod.GET,"/documents?categoryId="+category,null,student),200,0).path("total").asInt());
        assertEquals(1,body(call(HttpMethod.GET,"/admin/documents?categoryId="+category+"&status=OFFLINE",null,admin),200,0).path("total").asInt());
        body(call(HttpMethod.GET,"/documents/"+a+"/download",null,admin),404,40401);
        body(call(HttpMethod.DELETE,"/categories/"+category,null,admin),409,40903);
        for(String query:List.of("page=0","pageSize=51","categoryId=9007199254740992","keyword="+"x".repeat(101),"userId=1","status=ACTIVE","page=1&page=2"))
            body(call(HttpMethod.GET,"/documents?"+query,null,student),422,42201);
        for(String path:List.of("/documents/0","/documents/"+b+"/status?owner=1","/documents/"+b+"/chunks?pageSize=51",
                "/documents/"+b+"/chunks?chunkIndex=0","/documents/"+b+"/download?filePath=ignored"))
            body(call(HttpMethod.GET,path,null,student),422,42201);
    }
    @Test void unicodeContractLimitsAreStoredAndPaginationIsStable() throws Exception {
        String name="😀".repeat(251)+".txt",title="😀".repeat(200);
        body(upload("😀".repeat(252)+".txt","text/plain","original".getBytes(),title,category,admin),422,42201);
        long id=accepted(name,"text/plain","original".getBytes(),title).path("id").asLong();terminal(id,"READY");
        var detail=body(call(HttpMethod.GET,"/documents/"+id,null,student),200,0);
        assertEquals(title,detail.path("title").asText());assertEquals(name,detail.path("currentVersion").path("originalFilename").asText());
        for(int i=0;i<10;i++) {long next=accepted("part.txt","text/plain","original".getBytes(),prefix+"_"+i).path("id").asLong();terminal(next,"READY");}
        var page=body(call(HttpMethod.GET,"/documents?categoryId="+category,null,student),200,0);
        assertEquals(11,page.path("total").asInt());assertEquals(10,page.path("items").size());
        var second=body(call(HttpMethod.GET,"/documents?categoryId="+category+"&page=2",null,student),200,0);
        assertEquals(1,second.path("items").size());assertEquals(id,second.path("items").get(0).path("id").asLong());
    }
    @Test void statusSnapshotNeverMixesReadyVersionWithMissingCurrentPointer() throws Exception {
        for(int i=0;i<20;i++) {
            var accepted=accepted("fast.txt","text/plain","fast original".getBytes(),prefix+" snapshot");long id=accepted.path("id").asLong();
            var done=terminal(id,"READY");assertTrue(done.path("currentVersion").path("id").asLong()>0);
            assertEquals(done.path("latestVersion").path("id"),done.path("currentVersion").path("id"));
        }
    }
}
