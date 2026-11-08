package com.fast.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import com.fast.knowledge.config.CourseComponentFilter;

@SpringBootApplication
@ComponentScan(basePackages = "com.fast.knowledge", excludeFilters =
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = CourseComponentFilter.class))
@EnableAsync
@EnableScheduling
public class KnowledgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeApplication.class, args);
    }
}
