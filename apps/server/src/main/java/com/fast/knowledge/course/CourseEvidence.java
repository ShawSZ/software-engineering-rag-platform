package com.fast.knowledge.course;

public record CourseEvidence(String label,long documentId,long versionId,int versionNumber,long chunkId,
        String documentTitle,Integer pageNo,int chunkIndex,String content) { }
