package com.fast.knowledge.course;

import com.fasterxml.jackson.databind.*;
import com.fast.knowledge.common.*;
import com.fast.knowledge.security.UserContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class CourseStudyService {
    private final JdbcTemplate jdbc;
    private final CourseStudyGateway model;
    private final CourseQaStore store;
    private final CourseCitationValidator citations;
    private final ObjectMapper json;
    public CourseStudyService(JdbcTemplate jdbc,CourseStudyGateway model,CourseQaStore store,CourseCitationValidator citations,ObjectMapper json) {
        this.jdbc=jdbc;this.model=model;this.store=store;this.citations=citations;this.json=json;
    }
    public record Result(String kind,long documentId,int selectedChunkCount,int totalChunkCount,CourseQaStore.Detail result) { }
    public Result generate(Long documentId,String kind,Integer questionCount) {
        if(documentId==null || kind==null || !Set.of("OUTLINE","EXERCISES").contains(kind)) throw ApiException.invalid();
        CourseInputs.id(documentId);
        int count=questionCount==null?3:questionCount;
        if(count<1 || count>5 || (kind.equals("OUTLINE") && questionCount!=null)) throw ApiException.invalid();
        // Pin a version before selecting a bounded, evenly distributed set of original chunks.
        var rows=jdbc.queryForList("SELECT d.title,v.id version_id,v.version_number,v.chunk_count FROM kb_document d JOIN kb_document_version v "
                +"ON v.id=d.current_version_id AND v.document_id=d.id WHERE d.id=? AND d.kb_id=0 AND d.enabled=1 AND v.status='READY'",documentId);
        if(rows.isEmpty()) throw ApiException.notFound();
        var document=rows.getFirst();long version=((Number)document.get("version_id")).longValue();
        int total=((Number)document.get("chunk_count")).intValue();
        if(total<1) throw ApiException.notFound();
        int amount=Math.min(24,total);var positions=new TreeSet<Integer>();
        for(int i=0;i<amount;i++) positions.add(amount==1?1:1+(int)Math.round(i*(total-1.0)/(amount-1)));
        var args=new ArrayList<Object>();args.add(documentId);args.add(version);args.addAll(positions);
        String placeholders=String.join(",",Collections.nCopies(positions.size(),"?"));
        var evidence=jdbc.query("SELECT id,chunk_index,page_no,content FROM kb_document_chunk WHERE document_id=? AND version_id=? "
                +"AND chunk_index IN ("+placeholders+") ORDER BY chunk_index",(r,n)->new CourseEvidence("S"+(n+1),documentId,version,
                ((Number)document.get("version_number")).intValue(),r.getLong("id"),(String)document.get("title"),
                (Integer)r.getObject("page_no"),r.getInt("chunk_index"),r.getString("content")),args.toArray());
        if(evidence.size()!=amount) throw new ApiException(40901,"资料正在变化，请刷新后重试");
        long start=System.nanoTime();CourseQaOutcome outcome;
        try {outcome=validate(model.generateStudy(kind,count,evidence,Duration.ofSeconds(30)),kind,count,evidence);}
        catch(CourseModelGateway.Failure failure){outcome=CourseQaOutcome.failed(failure.code());}
        catch(Exception failure){outcome=CourseQaOutcome.failed("INVALID_GENERATION");}
        String question=(kind.equals("OUTLINE")?"[复习提纲] ":"[练习题 "+count+"题] ")+document.get("title");
        long id;
        try {id=store.save(UserContext.currentUserId(),question,outcome,evidence,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));}
        catch(Exception failure){throw new ApiException(50002,"生成结果保存失败，请稍后重试");}
        return new Result(kind,documentId,evidence.size(),total,store.detail(id));
    }
    CourseQaOutcome validate(String raw,String kind,int count,List<CourseEvidence> evidence) {
        try {
            JsonNode root=json.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
            if(!fields(root,Set.of("status","title","items")) || !root.path("items").isArray()) return invalid();
            if("INSUFFICIENT".equals(root.path("status").asText()))
                return root.path("title").isNull() && root.path("items").isEmpty()?CourseQaOutcome.insufficient():invalid();
            if(!"GENERATED".equals(root.path("status").asText()) || !text(root.path("title"),120)) return invalid();
            var items=root.path("items");boolean outline=kind.equals("OUTLINE");
            if(outline?(items.size()<3 || items.size()>8):items.size()!=count) return invalid();
            var allowed=new HashSet<String>();evidence.forEach(e->allowed.add(e.label()));
            var used=new LinkedHashSet<String>();var seen=new HashSet<String>();
            var markdown=new StringBuilder("# ").append(root.path("title").asText().replace('\n',' ')).append("\n\n");
            int index=0;
            for(var item:items) {
                String key=outline?"heading":"question",body=outline?"content":"answer";
                if(!fields(item,Set.of(key,body,"citationIds")) || !text(item.path(key),300) || !text(item.path(body),1500)
                        || !item.path("citationIds").isArray() || item.path("citationIds").isEmpty()
                        || !seen.add(item.path(key).asText().strip())) return invalid();
                var ids=new LinkedHashSet<String>();
                for(var id:item.path("citationIds")) if(!id.isTextual() || !allowed.contains(id.asText()) || !ids.add(id.asText())) return CourseQaOutcome.failed("INVALID_CITATION");
                var markers=Pattern.compile("\\[(S[^\\]]*)\\]").matcher(item.path(key).asText()+item.path(body).asText());
                while(markers.find()) if(!ids.contains(markers.group(1))) return CourseQaOutcome.failed("INVALID_CITATION");
                used.addAll(ids);markdown.append("## ").append(++index).append(". ").append(item.path(key).asText().replace('\n',' ')).append("\n\n");
                if(!outline) markdown.append("**参考答案：**");
                markdown.append(item.path(body).asText()).append("\n\n");
                ids.forEach(id->markdown.append('[').append(id).append(']'));markdown.append("\n\n");
            }
            return citations.validate(json.writeValueAsString(Map.of("status","ANSWER","answer",markdown.toString(),"citationIds",used)),evidence);
        } catch(Exception failure){return invalid();}
    }
    private static boolean fields(JsonNode value,Set<String> expected) {
        if(!value.isObject()) return false;var fields=new HashSet<String>();value.fieldNames().forEachRemaining(fields::add);return fields.equals(expected);
    }
    private static boolean text(JsonNode value,int max) {return value.isTextual() && !value.asText().isBlank() && value.asText().codePointCount(0,value.asText().length())<=max;}
    private static CourseQaOutcome invalid(){return CourseQaOutcome.failed("INVALID_GENERATION");}
}
