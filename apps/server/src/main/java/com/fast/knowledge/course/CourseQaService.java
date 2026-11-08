package com.fast.knowledge.course;

import com.fast.knowledge.common.ApiException;
import com.fast.knowledge.security.UserContext;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.*;

@Service
public class CourseQaService {
    private final CourseKeywordRetriever retrieval;
    private final CourseModelGateway model;
    private final CourseCitationValidator citations;
    private final CourseQaStore store;
    public CourseQaService(CourseKeywordRetriever retrieval,CourseModelGateway model,CourseCitationValidator citations,CourseQaStore store) {
        this.retrieval=retrieval;this.model=model;this.citations=citations;this.store=store;
    }
    public CourseQaStore.Detail ask(String input) {
        return ask(input,null);
    }
    public CourseQaStore.Detail ask(String input,Long parentQaId) {
        String question=CourseInputs.text(input,500);long userId=UserContext.currentUserId();long start=System.nanoTime();
        var previous=parentQaId==null?List.<CourseQaStore.Detail>of():store.conversation(parentQaId);
        if(previous.size()>=100) throw new ApiException(42201,"对话已达100轮，请开始新对话");
        var history=new ArrayList<CourseModelGateway.Turn>();int remaining=6000;
        for(int i=previous.size()-1;i>=0 && history.size()<6 && remaining>0;i--) {
            var turn=previous.get(i);
            if(!"SUCCESS".equals(turn.status())) continue;
            String answer=turn.sources().stream().allMatch(s->"CURRENT".equals(s.availability()))?turn.answer():"";
            answer=clip(answer,Math.min(1200,remaining));remaining-=answer.codePointCount(0,answer.length());
            history.addFirst(new CourseModelGateway.Turn(turn.question(),answer));
        }
        String query=question,anchor=null;
        if(followup(question)) for(int i=previous.size()-1;i>=0;i--) {
            var turn=previous.get(i);
            if("SUCCESS".equals(turn.status()) && !followup(turn.question())) {anchor=turn.question();query=question+" "+anchor;break;}
        }
        List<CourseEvidence> evidence=List.of();CourseQaOutcome result;
        try {
            evidence=retrieval.retrieve(query);
            // Short elliptical questions must not dilute a precise previous topic below the lexical threshold.
            if(evidence.isEmpty() && anchor!=null) evidence=retrieval.retrieve(anchor);
            result=evidence.isEmpty()?CourseQaOutcome.insufficient():null;
        }
        catch(Exception e) {result=CourseQaOutcome.failed("RETRIEVAL_ERROR");}
        if(result==null) try {result=citations.validate(history.isEmpty()?model.generate(question,evidence,Duration.ofSeconds(30)):
                model.generate(question,evidence,history,Duration.ofSeconds(30)),evidence);}
        catch(CourseModelGateway.Failure e) {result=CourseQaOutcome.failed(e.code());}
        catch(Exception e) {result=CourseQaOutcome.failed("INTERNAL_ERROR");}
        long elapsed=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),id;
        try {id=store.save(userId,question,result,evidence,elapsed,parentQaId);}
        catch(Exception e) {throw new ApiException(50002,"问答记录保存失败，请稍后重试");}
        return store.detail(id);
    }
    static boolean followup(String question) {
        return question.matches("(?is).*(它|他们|它们|这个|那个|这种|那种|上述|上面|刚才|前面|继续|举个|举例|再解释|详细|具体|进一步|换个|还有呢|为什么呢|例子).*" )
                || question.matches("(?is)^(那|那么|这|其|再|还有|然后|why\\b|how about\\b|what about\\b).*" )
                || question.matches("(?is).*\\b(it|they|that|this|example|examples)\\b.*");
    }
    private static String clip(String text,int limit) {
        if(text==null) return "";int size=text.codePointCount(0,text.length());return size<=limit?text:text.substring(0,text.offsetByCodePoints(0,limit));
    }
}
