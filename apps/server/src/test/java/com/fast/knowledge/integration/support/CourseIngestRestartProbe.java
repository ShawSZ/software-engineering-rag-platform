package com.fast.knowledge.integration.support;

import com.fast.knowledge.KnowledgeApplication;
import com.fast.knowledge.course.*;
import com.fast.knowledge.security.UserContext;
import com.fast.knowledge.storage.StorageProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Two isolated processes deliberately simulate a crash before parsing completes. */
public class CourseIngestRestartProbe {
    public static void main(String[] args) throws Exception {
        com.fast.knowledge.testguard.CourseTestDatabaseGuardFactory.verifyFileArguments(args);
        boolean seed="seed".equals(System.getenv("B03_TEST_MODE"));
        try(var context=SpringApplication.run(KnowledgeApplication.class,args)) {
            JdbcTemplate jdbc=context.getBean(JdbcTemplate.class);
            var service=context.getBean(CourseDocumentService.class);
            var tasks=context.getBean(CourseIngestTasks.class);
            UserContext user=new UserContext();user.setUserId(1L);user.setRole("ADMIN");UserContext.set(user);
            if(seed) {
                long category=context.getBean(CourseCategoryService.class).save(null,"Restart fixtures",0).getId();
                for(int i=0;i<2;i++) {
                    var doc=service.upload(new MockMultipartFile("file","fixture.txt","text/plain",("restart original "+i).getBytes(StandardCharsets.UTF_8)),"Restart "+i,category);
                    if(i==0) {
                        long id=jdbc.queryForObject("SELECT id FROM kb_index_task WHERE version_id=?",Long.class,doc.latestVersion().id());
                        var claim=tasks.claim(id);tasks.append(claim,List.of(new CourseFileParser.Chunk(1,null,"stale partial")));
                        jdbc.update("UPDATE kb_index_task SET locked_at=DATEADD('SECOND',-10,CURRENT_TIMESTAMP) WHERE id=?",id);
                    }
                }
                Runtime.getRuntime().halt(0); // No application shutdown hooks or connection cleanup.
            }
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while(System.nanoTime()<deadline && jdbc.queryForObject("SELECT COUNT(*) FROM kb_index_task WHERE status='DONE'",Integer.class)<2)
                Thread.sleep(50);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM kb_index_task WHERE status='DONE'",Integer.class)!=2) throw new AssertionError("Recovery did not finish");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM kb_document_version",Integer.class)!=2) throw new AssertionError("Version duplicated");
            var documents=service.list(1,10,null,null,null,false,null);
            if(documents.total()!=2) throw new AssertionError("Recovered documents not visible");
            for(var doc:documents.items()) {
                String original="restart original "+doc.title().substring(doc.title().length()-1);
                var chunks=service.chunks(doc.id(),1,10,null);
                if(chunks.total()!=1 || !original.equals(chunks.items().getFirst().content())) throw new AssertionError("Stale/duplicated chunks after recovery");
                if(!Arrays.equals(original.getBytes(StandardCharsets.UTF_8),service.download(doc.id()).bytes())) throw new AssertionError("Original file changed");
            }
            UserContext.clear();
            System.out.println("B03_RESTART_RECOVERY_PASS");
        }
    }
}
