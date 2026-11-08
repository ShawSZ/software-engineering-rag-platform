package com.fast.knowledge.course;

import java.time.Duration;
import java.util.List;

public interface CourseModelGateway {
    record Turn(String question,String answer) { }
    String generate(String question,List<CourseEvidence> evidence,Duration deadline);
    default String generate(String question,List<CourseEvidence> evidence,List<Turn> history,Duration deadline) {
        return generate(question,evidence,deadline);
    }
    final class Failure extends RuntimeException {
        private final String code;
        public Failure(String code) {super(code);this.code=code;}
        public String code() {return code;}
    }
}
