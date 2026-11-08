package com.fast.knowledge.course;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fast.knowledge.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
public class CourseStudyController {
    private final CourseStudyService study;
    public CourseStudyController(CourseStudyService study){this.study=study;}
    public record Generate(@JsonDeserialize(using=CourseDocumentController.PositiveId.class) Long documentId,
            @JsonDeserialize(using=AccountRequests.Text.class) String kind,
            @JsonDeserialize(using=CourseInputs.NonNegativeInteger.class) Integer questionCount) { }
    @PostMapping("/study")
    public ApiResponse<CourseStudyService.Result> generate(@RequestBody Generate body,HttpServletRequest request) {
        CourseAuthController.query(request,Set.of());return ApiResponse.ok(study.generate(body.documentId(),body.kind(),body.questionCount()));
    }
}
