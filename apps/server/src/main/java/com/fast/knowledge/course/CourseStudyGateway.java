package com.fast.knowledge.course;

import java.time.Duration;
import java.util.List;

public interface CourseStudyGateway {
    String generateStudy(String kind,int questionCount,List<CourseEvidence> evidence,Duration deadline);
}
