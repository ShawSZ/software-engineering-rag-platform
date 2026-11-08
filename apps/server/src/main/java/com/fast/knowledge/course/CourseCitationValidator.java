package com.fast.knowledge.course;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class CourseCitationValidator {
    private final ObjectMapper json;
    public CourseCitationValidator(ObjectMapper json) {this.json=json;}
    public CourseQaOutcome validate(String raw,List<CourseEvidence> evidence) {
        try {
            JsonNode value=json.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(raw);var fields=new HashSet<String>();value.fieldNames().forEachRemaining(fields::add);
            if(!value.isObject() || !fields.equals(Set.of("status","answer","citationIds")) || !value.path("citationIds").isArray()) return invalid();
            if("INSUFFICIENT".equals(value.path("status").asText())) {
                if(!value.path("citationIds").isEmpty() || !(value.path("answer").isNull() || value.path("answer").isTextual())) return invalid();
                return CourseQaOutcome.insufficient();
            }
            if(!"ANSWER".equals(value.path("status").asText()) || !value.path("answer").isTextual()) return invalid();
            String answer=value.path("answer").asText();
            if(answer.isBlank() || !StandardCharsets.UTF_8.newEncoder().canEncode(answer)) return invalid();
            var allowed=new HashSet<String>();evidence.forEach(e->allowed.add(e.label()));var citations=new LinkedHashSet<String>();
            for(var id:value.path("citationIds")) if(!id.isTextual() || !allowed.contains(id.asText()) || !citations.add(id.asText())) return invalid();
            var markers=new HashSet<String>();var matcher=Pattern.compile("\\[(S[^\\]]*)\\]").matcher(answer);
            while(matcher.find()) markers.add(matcher.group(1));
            if(citations.isEmpty() || !markers.equals(citations)) return invalid();
            return new CourseQaOutcome("SUCCESS",answer,null,null,false,List.copyOf(citations));
        } catch(Exception e) {return invalid();}
    }
    private CourseQaOutcome invalid() {return CourseQaOutcome.failed("INVALID_CITATION");}
}
