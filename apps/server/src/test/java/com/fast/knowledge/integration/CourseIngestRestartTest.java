package com.fast.knowledge.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class CourseIngestRestartTest {
    @TempDir Path temporary;
    @Test void queuedAndAbandonedParsingTasksRecoverAfterImmediateJvmHalt() throws Exception {
        String secret=UUID.randomUUID().toString()+UUID.randomUUID();
        for(String mode:List.of("seed","recover")) {
            List<String> arguments=List.of("-cp",System.getProperty("java.class.path"),
                    "com.fast.knowledge.integration.support.CourseIngestRestartProbe","--spring.profiles.active=h2",
                    "--server.address=127.0.0.1","--server.port=0","--spring.datasource.username=sa","--spring.datasource.password=",
                    "--spring.datasource.url=jdbc:h2:file:"+temporary.resolve("course_rag_test").toString().replace('\\','/')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE",
                    "--knowledge.storage.local.base-dir="+temporary.resolve("files"),"--course.ingest.enabled="+mode.equals("recover"),
                    "--course.ingest.poll-ms=100","--course.ingest.lease-seconds=1","--knowledge.llm.base-url=","--knowledge.llm.api-key=");
            StringBuilder text=new StringBuilder();for(String argument:arguments)
                text.append('"').append(argument.replace("\\","\\\\").replace("\"","\\\"")).append("\"\n");
            Path argumentFile=temporary.resolve(mode+".args"),log=temporary.resolve(mode+".log");Files.writeString(argumentFile,text);
            var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"@"+argumentFile)
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            var environment=builder.environment();
            for(String name:new ArrayList<>(environment.keySet())) if(name.startsWith("SPRING_") || name.startsWith("DB_")
                    || name.startsWith("KNOWLEDGE_") || name.equals("JAVA_TOOL_OPTIONS") || name.equals("JDK_JAVA_OPTIONS")) environment.remove(name);
            environment.put("JWT_SECRET",secret);environment.put("APP_ADMIN_USERNAME","");environment.put("APP_ADMIN_PASSWORD","");environment.put("B03_TEST_MODE",mode);
            Process process=builder.start();
            try {
                assertTrue(process.waitFor(60,TimeUnit.SECONDS),"Isolated task recovery probe timed out");
                String output=Files.readString(log,StandardCharsets.UTF_8);
                assertEquals(0,process.exitValue(),output.replace(secret,"[redacted]"));assertFalse(output.contains(secret));
                if(mode.equals("recover")) assertTrue(output.contains("B03_RESTART_RECOVERY_PASS"));
            } finally {if(process.isAlive()) {process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}}
        }
    }
}
