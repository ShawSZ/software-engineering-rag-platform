package com.fast.knowledge.integration;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.*;
import com.fast.knowledge.cache.CacheProvider;
import com.fast.knowledge.config.KnowledgeProperties;
import com.fast.knowledge.course.*;
import com.fast.knowledge.mapper.*;
import com.fast.knowledge.model.entity.KbUser;
import com.fast.knowledge.security.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:course_rag_test_b02_accounts;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "knowledge.storage.local.base-dir=target/b02-files", "knowledge.security.rate-limit-enabled=false",
        "APP_ADMIN_USERNAME=", "APP_ADMIN_PASSWORD=", "knowledge.llm.base-url=", "knowledge.llm.api-key="})
@ActiveProfiles("h2")
class CourseAccountTest {
    static final String SECRET = UUID.randomUUID().toString() + UUID.randomUUID();
    static final String PASSWORD = "Fixture_" + UUID.randomUUID();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("knowledge.jwt.secret", () -> SECRET);
    }
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;
    @Autowired UserMapper users;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtUtil jwt;
    @Autowired CacheProvider cache;
    @Autowired SystemConfigMapper configs;
    @Autowired CourseAccountService accounts;
    KbUser admin;

    @BeforeEach void fixtures() throws Exception {
        try (var connection = source.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:course_rag_test_b02_accounts"));
        }
        // Only this named in-memory database may reset its account fixtures.
        jdbc.update("DELETE FROM kb_user"); jdbc.update("DELETE FROM kb_system_config");
        cache.deleteByPrefix("kb:token:blacklist:");
        admin = fixture("b02_admin", "ADMIN");
    }
    KbUser fixture(String name, String role) {
        KbUser user = new KbUser(); user.setUsername(name); user.setPassword(encoder.encode(PASSWORD));
        user.setDisplayName(name); user.setRole(role); user.setStatus(1); user.setAuthSource("LOCAL");
        user.setMustChangePassword(0); users.insert(user); return user;
    }
    String token(KbUser user) { return jwt.createToken(user.getId(), user.getUsername(), user.getRole(), user.getAuthVersion()); }
    ResponseEntity<String> request(HttpMethod method, String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange("/api" + path, method, new HttpEntity<>(body, headers), String.class);
    }
    JsonNode envelope(ResponseEntity<String> response, int status, int code) throws Exception {
        assertEquals(status, response.getStatusCode().value());
        var body = json.readTree(response.getBody()); assertEquals(code, body.path("code").asInt());
        assertEquals(response.getHeaders().getFirst("X-Request-Id"), body.path("requestId").asText());
        if (code != 0) assertTrue(body.path("data").isNull());
        return body.path("data");
    }
    JsonNode register(String username, String password, String displayName) throws Exception {
        Map<String,Object> body = new HashMap<>(Map.of("username",username,"password",password));
        if (displayName != null) body.put("displayName",displayName);
        return envelope(request(HttpMethod.POST,"/auth/register",body,null),201,0);
    }
    void userShape(JsonNode user) {
        Set<String> fields = new HashSet<>(); user.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("userId","username","displayName","role","status","createdAt"),fields);
        assertTrue(user.path("createdAt").asText().endsWith("+08:00"));
    }
    @Test void registrationIsStudentOnlySaltedAndDoesNotLogin() throws Exception {
        jdbc.update("DELETE FROM kb_user"); // Also verifies the first user never becomes an administrator.
        String password = "  padded password  ";
        var user = register("first_student",password,"  学生一  ");
        userShape(user); assertEquals("USER",user.path("role").asText());
        assertEquals("ENABLED",user.path("status").asText()); assertEquals("学生一",user.path("displayName").asText());
        assertFalse(user.has("token"));
        var second = register("second_student",password,null);
        assertEquals("second_student",second.path("displayName").asText());
        var stored = users.findByUsername("first_student");
        assertTrue(encoder.matches(password,stored.getPassword()));
        assertFalse(encoder.matches(password.strip(),stored.getPassword()));
        assertNotEquals(stored.getPassword(),users.findByUsername("second_student").getPassword());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM kb_user WHERE role='ADMIN'",Integer.class));
        envelope(request(HttpMethod.GET,"/auth/me",null,null),401,40101);
        var login = envelope(request(HttpMethod.POST,"/auth/login",Map.of("username","first_student","password",password),null),200,0);
        assertEquals("USER",login.path("role").asText());
    }
    @Test void rejectsPrivilegeFieldsUnknownQueryAndWrongJsonTypes() throws Exception {
        for (String field : List.of("role","status","userId","owner","kbId","passwordHash")) {
            var body = new HashMap<String,Object>(Map.of("username","injection_student","password",PASSWORD));
            body.put(field,"ADMIN");
            envelope(request(HttpMethod.POST,"/auth/register",body,null),422,42201);
        }
        envelope(request(HttpMethod.POST,"/auth/register?userId=1",Map.of("username","student_ok","password",PASSWORD),null),422,42201);
        envelope(request(HttpMethod.POST,"/auth/register",Map.of("username",123,"password",PASSWORD),null),422,42201);
        envelope(request(HttpMethod.POST,"/auth/login",Map.of("username","b02_admin","password",123456),null),422,42201);
        var explicitNull=new HashMap<String,Object>(Map.of("username","null_display_student","password",PASSWORD));
        explicitNull.put("displayName",null);
        envelope(request(HttpMethod.POST,"/auth/register",explicitNull,null),422,42201);
        assertNull(users.findByUsername("null_display_student"));
        assertNull(users.findByUsername("injection_student"));
    }
    @Test void registrationInputBoundariesIncludeBcryptByteLimit() throws Exception {
        for (String username : List.of("ab","a".repeat(33),"student-name"," 学生 "," abc")) {
            envelope(request(HttpMethod.POST,"/auth/register",Map.of("username",username,"password",PASSWORD),null),422,42201);
        }
        for (String password : List.of("12345","a".repeat(65),"密".repeat(25),"😀".repeat(19))) {
            envelope(request(HttpMethod.POST,"/auth/register",Map.of("username","invalid_password","password",password),null),422,42201);
        }
        for (String name : List.of("  ","名".repeat(65))) {
            envelope(request(HttpMethod.POST,"/auth/register",Map.of("username","invalid_display","password",PASSWORD,"displayName",name),null),422,42201);
        }
        register("abc","123456",null); register("x".repeat(32),"a".repeat(64),null);
        register("byte_limit","密".repeat(24),null);
        var emoji = register("unicode_name","😀".repeat(18),"😀".repeat(64));
        assertEquals("😀".repeat(64),emoji.path("displayName").asText());
    }
    @Test void duplicateUsernameAndConcurrentRegistrationAreAtomic() throws Exception {
        register("duplicate_user",PASSWORD,null);
        envelope(request(HttpMethod.POST,"/auth/register",Map.of("username","duplicate_user","password",PASSWORD),null),409,40901);
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            Callable<Integer> action = () -> { gate.await(); return request(HttpMethod.POST,"/auth/register",
                    Map.of("username","concurrent_student","password",PASSWORD),null).getStatusCode().value(); };
            var a=pool.submit(action); var b=pool.submit(action); gate.countDown();
            var statuses=new ArrayList<>(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))); Collections.sort(statuses);
            assertEquals(List.of(201,409),statuses);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM kb_user WHERE username='concurrent_student'",Integer.class));
        } finally { pool.shutdownNow(); }
    }
    @Test void loginMeAndFailureCodesDoNotRevealPasswordOrDisabledAccounts() throws Exception {
        var body=Map.of("username",admin.getUsername(),"password",PASSWORD);
        var a=envelope(request(HttpMethod.POST,"/auth/login",body,null),200,0);
        var b=envelope(request(HttpMethod.POST,"/auth/login",body,null),200,0);
        assertNotEquals(a.path("token").asText(),b.path("token").asText());
        userShape(envelope(request(HttpMethod.GET,"/auth/me",null,a.path("token").asText()),200,0));
        for(var wrong:List.of(Map.of("username","missing_user","password",PASSWORD),
                Map.of("username",admin.getUsername(),"password","wrong"))) {
            envelope(request(HttpMethod.POST,"/auth/login",wrong,null),401,40102);
        }
        jdbc.update("UPDATE kb_user SET status=0 WHERE id=?",admin.getId());
        envelope(request(HttpMethod.POST,"/auth/login",body,null),401,40102);
        envelope(request(HttpMethod.GET,"/auth/me",null,a.path("token").asText()),401,40103);
    }
    @Test void invalidExpiredMissingClaimsAndDeletedUserTokensAreRejected() throws Exception {
        var algorithm=Algorithm.HMAC256(SECRET);
        List<String> invalid=List.of("garbage",JWT.create().withClaim("userId",admin.getId()).withExpiresAt(new Date(1)).sign(algorithm),
                JWT.create().withClaim("userId",admin.getId()).sign(algorithm),
                JWT.create().withExpiresAt(new Date(System.currentTimeMillis()+60000)).sign(algorithm),
                JWT.create().withClaim("userId",admin.getId()).withExpiresAt(new Date(System.currentTimeMillis()+60000))
                        .sign(Algorithm.HMAC256("another-generated-test-key-"+UUID.randomUUID())));
        for(String invalidToken:invalid) envelope(request(HttpMethod.GET,"/auth/me",null,invalidToken),401,40101);
        var student=fixture("deleted_student","USER");String token=token(student);users.deleteById(student.getId());
        envelope(request(HttpMethod.GET,"/auth/me",null,token),401,40101);
    }
    @Test void studentIsDeniedEveryActiveManagementEndpointAndRoleComesFromDatabase() throws Exception {
        var student=fixture("plain_student","USER");String token=jwt.createToken(student.getId(),student.getUsername(),"ADMIN");
        for(String path:List.of("/admin/users","/admin/documents","/admin/feedback","/admin/statistics")) {
            envelope(request(HttpMethod.GET,path,null,token),403,40301);
        }
        for(String path:List.of("/documents","/categories")) envelope(request(HttpMethod.POST,path,Map.of(),token),403,40301);
        envelope(request(HttpMethod.PATCH,"/admin/users/"+admin.getId()+"/status",Map.of("status","DISABLED"),token),403,40301);
        String oldAdmin=token(admin);jdbc.update("UPDATE kb_user SET role='USER' WHERE id=?",admin.getId());
        envelope(request(HttpMethod.GET,"/admin/users",null,oldAdmin),403,40301);
    }
    @Test void adminListingUsesContractPaginationFiltersAndNeverReturnsHashes() throws Exception {
        for(int i=0;i<12;i++) fixture("student_"+i,"USER");
        jdbc.update("UPDATE kb_user SET display_name='needle label',status=0 WHERE username='student_5'");
        String token=token(admin);
        var first=envelope(request(HttpMethod.GET,"/admin/users",null,token),200,0);
        assertEquals(10,first.path("items").size());assertEquals(13,first.path("total").asInt());
        for(var user:first.path("items")) userShape(user);
        var filtered=envelope(request(HttpMethod.GET,"/admin/users?keyword=needle&status=DISABLED",null,token),200,0);
        assertEquals(1,filtered.path("total").asInt());assertEquals("student_5",filtered.path("items").get(0).path("username").asText());
        var beyond=envelope(request(HttpMethod.GET,"/admin/users?page=99&pageSize=10",null,token),200,0);
        assertEquals(13,beyond.path("total").asInt());assertEquals(0,beyond.path("items").size());
        for(String query:List.of("page=0","pageSize=51","status=UNKNOWN","keyword="+"a".repeat(101),"userId=1","page=1&page=2")) {
            envelope(request(HttpMethod.GET,"/admin/users?"+query,null,token),422,42201);
        }
        envelope(request(HttpMethod.DELETE,"/admin/users/"+admin.getId(),null,token),404,40401);
    }
    @Test void disablingRevokesSessionsEvenAfterReenableAndStatusIsStrict() throws Exception {
        var student=fixture("status_student","USER");String studentToken=token(student);String adminToken=token(admin);
        String path="/admin/users/"+student.getId()+"/status";
        for(Object status:List.of("UNKNOWN",1,true)) envelope(request(HttpMethod.PATCH,path,Map.of("status",status),adminToken),422,42201);
        envelope(request(HttpMethod.PATCH,path,Map.of("status","DISABLED","role","ADMIN"),adminToken),422,42201);
        envelope(request(HttpMethod.PATCH,path,Map.of("status","DISABLED"),adminToken),200,0);
        envelope(request(HttpMethod.GET,"/auth/me",null,studentToken),401,40103);
        envelope(request(HttpMethod.PATCH,path,Map.of("status","ENABLED"),adminToken),200,0);
        envelope(request(HttpMethod.GET,"/auth/me",null,studentToken),401,40101);
        envelope(request(HttpMethod.POST,"/auth/login",Map.of("username",student.getUsername(),"password",PASSWORD),null),200,0);
        envelope(request(HttpMethod.PATCH,"/admin/users/9007199254740991/status",Map.of("status","DISABLED"),adminToken),404,40401);
        for(String id:List.of("0","-1","9007199254740992","not-a-number")) {
            envelope(request(HttpMethod.PATCH,"/admin/users/"+id+"/status",Map.of("status","DISABLED"),adminToken),422,42201);
        }
    }
    @Test void lastAdminProtectionAllowsSelfDisableOnlyWhenAnotherAdminIsEnabled() throws Exception {
        String token=token(admin);String path="/admin/users/"+admin.getId()+"/status";
        envelope(request(HttpMethod.PATCH,path,Map.of("status","DISABLED"),token),409,40905);
        fixture("second_admin","ADMIN");
        envelope(request(HttpMethod.PATCH,path,Map.of("status","DISABLED"),token),200,0);
        envelope(request(HttpMethod.GET,"/admin/users",null,token),401,40103);
    }
    @Test void concurrentSelfDisablesAlwaysLeaveOneEnabledAdministrator() throws Exception {
        KbUser second=fixture("concurrent_admin","ADMIN");
        var pool=Executors.newFixedThreadPool(2);
        try {
            for(int round=0;round<5;round++) {
                jdbc.update("UPDATE kb_user SET status=1,auth_version=0");
                var gate=new CountDownLatch(1);
                Callable<Integer> a=()->{gate.await();return request(HttpMethod.PATCH,"/admin/users/"+admin.getId()+"/status",Map.of("status","DISABLED"),token(admin)).getStatusCode().value();};
                Callable<Integer> b=()->{gate.await();return request(HttpMethod.PATCH,"/admin/users/"+second.getId()+"/status",Map.of("status","DISABLED"),token(second)).getStatusCode().value();};
                var left=pool.submit(a);var right=pool.submit(b);gate.countDown();
                var statuses=new ArrayList<>(List.of(left.get(10,TimeUnit.SECONDS),right.get(10,TimeUnit.SECONDS)));Collections.sort(statuses);
                assertEquals(List.of(200,409),statuses);
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM kb_user WHERE role='ADMIN' AND status=1",Integer.class));
            }
        } finally { pool.shutdownNow(); }
    }
    @Test void logoutPersistsAndNewLoginIsIndependent() throws Exception {
        var body=Map.of("username",admin.getUsername(),"password",PASSWORD);
        String first=envelope(request(HttpMethod.POST,"/auth/login",body,null),200,0).path("token").asText();
        envelope(request(HttpMethod.POST,"/auth/logout",Map.of("userId",admin.getId()),first),422,42201);
        envelope(request(HttpMethod.GET,"/auth/me",null,first),200,0);
        envelope(request(HttpMethod.POST,"/auth/logout",null,first),200,0);
        cache.deleteByPrefix("kb:token:blacklist:");
        var freshCache=new com.fast.knowledge.cache.CaffeineCacheProvider(new KnowledgeProperties());
        assertTrue(new TokenBlacklistService(freshCache,configs,jwt).isBlacklisted(first));
        envelope(request(HttpMethod.GET,"/auth/me",null,first),401,40101);
        envelope(request(HttpMethod.POST,"/auth/logout",null,first),401,40101);
        String next=envelope(request(HttpMethod.POST,"/auth/login",body,null),200,0).path("token").asText();
        assertNotEquals(first,next);envelope(request(HttpMethod.GET,"/auth/me",null,next),200,0);
    }
    @Test void initializationIgnoresExistingAdminsAndNeverPromotesOccupiedStudentName() throws Exception {
        String original=users.selectById(admin.getId()).getPassword();
        jdbc.update("UPDATE kb_user SET status=0 WHERE id=?",admin.getId());
        assertFalse(accounts.initializeAdmin("replacement_admin",PASSWORD));
        assertEquals(original,users.selectById(admin.getId()).getPassword());assertEquals(0,users.selectById(admin.getId()).getStatus());
        jdbc.update("DELETE FROM kb_user");
        var first=register("occupied_student",PASSWORD,null);assertEquals("USER",first.path("role").asText());
        assertFalse(accounts.initializeAdmin("",PASSWORD));assertFalse(accounts.initializeAdmin("fresh_admin",""));
        assertThrows(IllegalStateException.class,()->accounts.initializeAdmin("occupied_student",PASSWORD));
        assertEquals("USER",users.findByUsername("occupied_student").getRole());
        assertTrue(accounts.initializeAdmin("fresh_admin",PASSWORD));
        assertFalse(accounts.initializeAdmin("another_admin",PASSWORD));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM kb_user WHERE role='ADMIN'",Integer.class));
    }
    @Test void concurrentInitializationCreatesOnlyOneAdministrator() throws Exception {
        jdbc.update("DELETE FROM kb_user");
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{gate.await();return accounts.initializeAdmin("bootstrap_one",PASSWORD);});
            var b=pool.submit(()->{gate.await();return accounts.initializeAdmin("bootstrap_two",PASSWORD);});
            gate.countDown();assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM kb_user WHERE role='ADMIN'",Integer.class));
        } finally { pool.shutdownNow(); }
    }
}
