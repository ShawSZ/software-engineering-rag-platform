package com.fast.knowledge.course;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded lexical retrieval for the current single-machine H2 deployment. No result cache. */
@Service
public class CourseKeywordRetriever {
    private final JdbcTemplate jdbc;
    private static final Set<String> STOP=Set.of("a","an","the","is","are","was","were","be","does","do","did","what","why","how","which","when","where","who","to","of","in","on","and","or","for","with","from","it","as","by","can","you","please","explain","describe");
    public CourseKeywordRetriever(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public List<String> terms(String question) {
        String value=question.toLowerCase(Locale.ROOT).replaceAll("为什么|是什么|什么|如何|怎么|哪些|有哪些|请问|请解释|的|了|吗|呢","");
        var found=new LinkedHashSet<String>();var words=Pattern.compile("[a-z0-9_]+|[\\p{IsHan}]+").matcher(value);
        while(words.find() && found.size()<32) {
            String token=words.group();
            if(token.codePoints().allMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN)) {
                int[] points=token.codePoints().toArray();
                if(points.length==1) found.add(token);
                else for(int i=0;i<points.length-1 && found.size()<32;i++) found.add(new String(points,i,2));
            } else if(!STOP.contains(token)) found.add(token);
        }
        return List.copyOf(found);
    }
    public List<CourseEvidence> retrieve(String question) {
        var terms=terms(question);if(terms.isEmpty()) return List.of();
        var args=new ArrayList<Object>();var score=new StringJoiner("+");
        for(String term:terms) {
            score.add("(CASE WHEN LOCATE(?,LOWER(c.content))>0 THEN 2 ELSE 0 END + CASE WHEN LOCATE(?,LOWER(d.title))>0 THEN 1 ELSE 0 END)");
            args.add(term);args.add(term);
        }
        int minimum=Math.max(2,(int)Math.ceil(terms.size()*0.35)*2);args.add(minimum);
        var matches=jdbc.query("SELECT * FROM (SELECT d.id document_id,v.id version_id,v.version_number,c.id chunk_id,d.title,c.page_no,c.chunk_index,c.content,"
                +score+" score FROM kb_document d JOIN kb_document_version v ON v.id=d.current_version_id AND v.document_id=d.id "
                +"JOIN kb_document_chunk c ON c.document_id=d.id AND c.version_id=v.id WHERE d.kb_id=0 AND d.enabled=1 AND v.status='READY') ranked "
                +"WHERE score>=? ORDER BY score DESC,document_id DESC,chunk_index LIMIT 5",
                (r,n)->new CourseEvidence("S"+(n+1),r.getLong("document_id"),r.getLong("version_id"),r.getInt("version_number"),r.getLong("chunk_id"),
                        r.getString("title"),(Integer)r.getObject("page_no"),r.getInt("chunk_index"),r.getString("content")),args.toArray());
        var result=new ArrayList<CourseEvidence>();int remaining=6000;
        for(var evidence:matches) {
            if(remaining==0) break;
            String text=evidence.content();int size=Math.min(remaining,text.codePointCount(0,text.length()));
            text=text.substring(0,text.offsetByCodePoints(0,size));remaining-=size;
            result.add(new CourseEvidence(evidence.label(),evidence.documentId(),evidence.versionId(),evidence.versionNumber(),evidence.chunkId(),
                    evidence.documentTitle(),evidence.pageNo(),evidence.chunkIndex(),text));
        }
        return result;
    }
}
