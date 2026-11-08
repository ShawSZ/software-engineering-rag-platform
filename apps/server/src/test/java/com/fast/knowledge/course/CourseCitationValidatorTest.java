package com.fast.knowledge.course;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class CourseCitationValidatorTest {
    final CourseCitationValidator validator=new CourseCitationValidator(new ObjectMapper());
    final List<CourseEvidence> evidence=List.of(new CourseEvidence("S1",1,1,1,1,"Title",null,1,"Original"));
    @Test void acceptsMatchingReferenceAndKeepsGeneratedTextSeparateFromOriginal() {
        var result=validator.validate("{\"status\":\"ANSWER\",\"answer\":\"Supported answer [S1]\",\"citationIds\":[\"S1\"]}",evidence);
        assertEquals("SUCCESS",result.status());assertEquals(List.of("S1"),result.citations());
    }
    @Test void rejectsUnknownMissingDuplicateReferencesInvalidJsonAndFakeExcerptFields() {
        for(String raw:List.of("not-json","{\"status\":\"ANSWER\",\"answer\":\"Text [S99]\",\"citationIds\":[\"S99\"]}",
                "{\"status\":\"ANSWER\",\"answer\":\"Text\",\"citationIds\":[\"S1\"]}",
                "{\"status\":\"ANSWER\",\"answer\":\"Text [S1]\",\"citationIds\":[]}",
                "{\"status\":\"ANSWER\",\"answer\":\"Text [S1]\",\"citationIds\":[\"S1\",\"S1\"]}",
                "{\"status\":\"ANSWER\",\"answer\":\"Text [S1]\",\"citationIds\":[\"S1\"],\"excerpt\":\"invented\"}")) {
            var result=validator.validate(raw,evidence);assertEquals("INVALID_CITATION",result.errorCode());assertNull(result.answer());
        }
    }
    @Test void insufficientNeverReturnsModelGeneratedAnswer() {
        var result=validator.validate("{\"status\":\"INSUFFICIENT\",\"answer\":\"untrusted text\",\"citationIds\":[]}",evidence);
        assertEquals("NO_EVIDENCE",result.status());assertNull(result.answer());assertFalse(result.retryable());
    }
}
