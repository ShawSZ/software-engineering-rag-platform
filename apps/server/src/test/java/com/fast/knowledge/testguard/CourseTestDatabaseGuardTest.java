package com.fast.knowledge.testguard;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class CourseTestDatabaseGuardTest {
    @Test void acceptsOnlyExplicitIsolatedMemoryNamesAndSafeOptions() {
        CourseTestDatabaseGuardFactory.verifyMemory("jdbc:h2:mem:course_rag_test_123;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        for(String url:new String[]{"jdbc:h2:file:./data/db/fast_knowledge","jdbc:h2:mem:production","jdbc:mysql://localhost/course_rag",
                "jdbc:h2:mem:course_rag_test_123;INIT=DROP ALL OBJECTS"})
            assertThrows(IllegalStateException.class,()->CourseTestDatabaseGuardFactory.verifyMemory(url));
    }
    @Test void rejectsUnsafeConfigurationBeforeInstantiatingAnyDatabaseBean() {
        var created=new AtomicBoolean();
        try(var context=new GenericApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("unsafe-test",Map.of("spring.datasource.url","jdbc:h2:file:./data/db/fast_knowledge")));
            context.registerBean("dataSource",Object.class,()->{created.set(true);return new Object();});
            CourseTestDatabaseGuardFactory.attach(context);
            assertThrows(IllegalStateException.class,context::refresh);assertFalse(created.get());
        }
    }
}
