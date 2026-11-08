package com.fast.knowledge.integration;

import com.fast.knowledge.config.KnowledgeProperties;
import com.fast.knowledge.security.JwtUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class PersistentLogoutTest {
    @TempDir Path temporary;
    @Test void revocationSurvivesImmediateJvmHaltAndFreshApplicationStartup() throws Exception {
        String secret=UUID.randomUUID().toString()+UUID.randomUUID();
        var properties=new KnowledgeProperties();properties.getJwt().setSecret(secret);
        String token=new JwtUtil(properties).createToken(1L,"fixture_user","USER");
        String second=new JwtUtil(properties).createToken(2L,"fixture_other","USER");
        run("write",secret,token,second);
        run("read-write",secret,token,second);
        run("read",secret,second,second);
    }
    private void run(String mode,String secret,String token,String second) throws Exception {
        String database="jdbc:h2:file:"+temporary.resolve("course_rag_test").toString().replace('\\','/')
                +";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        List<String> arguments=List.of("-cp",System.getProperty("java.class.path"),
                "com.fast.knowledge.integration.support.PersistentLogoutProbe",
                "--spring.profiles.active=h2","--server.address=127.0.0.1","--server.port=0",
                "--spring.datasource.url="+database,"--spring.datasource.username=sa","--spring.datasource.password=",
                "--knowledge.storage.local.base-dir="+temporary.resolve("files"));
        Path argumentFile=temporary.resolve(mode+".args");
        StringBuilder text=new StringBuilder();
        for(String argument:arguments) text.append('"').append(argument.replace("\\","\\\\").replace("\"","\\\"")).append("\"\n");
        Files.writeString(argumentFile,text);
        Path log=temporary.resolve(mode+".log");
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"@"+argumentFile)
                .redirectErrorStream(true).redirectOutput(log.toFile());
        var environment=builder.environment();
        for(String name:List.of("SPRING_CONFIG_ADDITIONAL_LOCATION","SPRING_CONFIG_LOCATION","SPRING_CONFIG_IMPORT",
                "JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS")) environment.remove(name);
        environment.put("JWT_SECRET",secret);environment.put("APP_ADMIN_USERNAME","");environment.put("APP_ADMIN_PASSWORD","");
        environment.put("B02_TEST_MODE",mode);environment.put("B02_TEST_TOKEN",token);
        environment.put("B02_TEST_SECOND_TOKEN",second);
        Process process=builder.start();
        try {
            assertTrue(process.waitFor(60,TimeUnit.SECONDS),"Isolated revocation probe timed out");
            String output=new String(Files.readAllBytes(log),java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0,process.exitValue(),output.replace(secret,"[redacted]").replace(token,"[redacted]").replace(second,"[redacted]"));
            assertFalse(output.contains(secret));assertFalse(output.contains(token));assertFalse(output.contains(second));
        } finally {
            if(process.isAlive()) {process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}
        }
    }
}
