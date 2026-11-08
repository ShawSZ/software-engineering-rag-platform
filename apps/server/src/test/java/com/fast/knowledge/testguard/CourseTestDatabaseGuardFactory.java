package com.fast.knowledge.testguard;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import java.nio.file.Path;
import java.util.List;

/** Runs before any datasource/migration/fixture bean can be created. Test code only. */
public final class CourseTestDatabaseGuardFactory implements ContextCustomizerFactory {
    @Override public ContextCustomizer createContextCustomizer(Class<?> testClass,List<ContextConfigurationAttributes> attributes) {
        if(!testClass.getName().startsWith("com.fast.knowledge.integration.Course")
                && !testClass.getSimpleName().equals("H2MinimalProfileBootTest")) return null;
        return (context,configuration)->attach(context);
    }
    public static void attach(ConfigurableApplicationContext context) {
        context.addBeanFactoryPostProcessor(factory->verifyMemory(context.getEnvironment().getProperty("spring.datasource.url")));
    }
    public static void verifyMemory(String url) {
        if(url==null || !url.matches("jdbc:h2:mem:course_rag_test_[A-Za-z0-9_-]+(?:;(?:MODE=MySQL|DATABASE_TO_LOWER=TRUE|CASE_INSENSITIVE_IDENTIFIERS=TRUE|DB_CLOSE_DELAY=-1))*"))
            throw new IllegalStateException("Tests require an explicitly named, isolated course_rag_test H2 memory database");
    }
    public static void verifyFileArguments(String[] arguments) {
        String prefix="--spring.datasource.url=jdbc:h2:file:";
        String url=java.util.Arrays.stream(arguments).filter(value->value.startsWith(prefix)).findFirst()
                .orElseThrow(()->new IllegalStateException("Missing isolated test database"));
        Path file=Path.of(url.substring(prefix.length()).split(";",2)[0]).toAbsolutePath().normalize();
        Path temporary=Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if(!file.startsWith(temporary) || !file.getFileName().toString().equals("course_rag_test")
                || !file.getParent().getFileName().toString().startsWith("junit-"))
            throw new IllegalStateException("Process tests require their JUnit temporary course_rag_test database");
    }
}
