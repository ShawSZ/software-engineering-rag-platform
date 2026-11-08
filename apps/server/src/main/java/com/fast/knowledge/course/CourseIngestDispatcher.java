package com.fast.knowledge.course;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name="course.ingest.enabled",havingValue="true",matchIfMissing=true)
public class CourseIngestDispatcher {
    private final CourseIngestTasks tasks;
    private final CourseIngestWorker worker;
    private final Set<Long> submitted=ConcurrentHashMap.newKeySet();
    public CourseIngestDispatcher(CourseIngestTasks tasks,CourseIngestWorker worker) {this.tasks=tasks;this.worker=worker;}
    @TransactionalEventListener
    public void afterCommit(CourseDocumentWriter.Queued event) {submit(event.taskId());}
    @Scheduled(fixedDelayString="${course.ingest.poll-ms:2000}",initialDelayString="${course.ingest.poll-ms:2000}")
    public void poll() {for(long id:tasks.pending()) submit(id);}
    private void submit(long id) {
        if(!submitted.add(id)) return;
        try {worker.run(id).whenComplete((ignored,error)->submitted.remove(id));}
        catch(RuntimeException e) {submitted.remove(id); /* Committed task remains available for the next poll. */}
    }
}
