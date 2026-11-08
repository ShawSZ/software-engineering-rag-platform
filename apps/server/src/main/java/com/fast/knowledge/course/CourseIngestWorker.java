package com.fast.knowledge.course;

import com.fast.knowledge.storage.StorageProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.util.concurrent.CompletableFuture;

@Service
public class CourseIngestWorker {
    private final CourseIngestTasks tasks;
    private final CourseFileParser parser;
    private final StorageProvider storage;
    public CourseIngestWorker(CourseIngestTasks tasks,CourseFileParser parser,StorageProvider storage) {
        this.tasks=tasks;this.parser=parser;this.storage=storage;
    }
    @Async("indexExecutor")
    public CompletableFuture<Void> run(long id) {
        CourseIngestTasks.Claim claim=null;
        try {
            claim=tasks.claim(id);
            if(claim==null) return CompletableFuture.completedFuture(null);
            byte[] bytes;
            try(var input=storage.openInputStream(claim.key())) {
                bytes=input.readNBytes((int)CourseFileParser.MAX_BYTES+1);
            }
            if(bytes.length>CourseFileParser.MAX_BYTES) throw new CourseFileParser.ParseFailure("PARSE_ERROR","存储文件大小不符合要求，请重新上传");
            if(claim.sha256()!=null && !claim.sha256().equals(CourseFileParser.sha256(bytes)))
                throw new CourseFileParser.ParseFailure("PARSE_ERROR","原文件校验失败，请重新上传");
            var chunks=parser.parse(bytes,claim.type());
            for(int offset=0;offset<chunks.size();offset+=200) {
                if(!tasks.append(claim,chunks.subList(offset,Math.min(offset+200,chunks.size())))) return CompletableFuture.completedFuture(null);
            }
            tasks.complete(claim,chunks.size());
        } catch(Exception error) {
            if(claim!=null) {
                String code=error instanceof CourseFileParser.ParseFailure failure?failure.code():"PARSE_ERROR";
                String message=error instanceof CourseFileParser.ParseFailure?error.getMessage():"解析失败，请检查原文件后重新上传";
                // A separate transaction persists failure; a database outage leaves a recoverable lease.
                try {tasks.fail(claim,code,message);}
                catch(Exception ignored) {org.slf4j.LoggerFactory.getLogger(getClass()).error("Ingest failure state could not be persisted; lease will recover");}
            }
        }
        return CompletableFuture.completedFuture(null);
    }
}
