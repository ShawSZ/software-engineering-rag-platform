package com.fast.knowledge.course;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fast.knowledge.common.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.Set;

@RestController
public class CoursePersonalController {
    private final CoursePersonalService personal;private final CourseAdministrationService administration;
    public CoursePersonalController(CoursePersonalService personal,CourseAdministrationService administration) {this.personal=personal;this.administration=administration;}
    @GetMapping("/favorites")
    public ApiResponse<PageResult<CourseQaStore.Summary>> favorites(@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="10") long pageSize,HttpServletRequest request) {
        CourseAuthController.query(request,Set.of("page","pageSize"));return ApiResponse.ok(personal.favorites(page,pageSize));
    }
    @PutMapping("/qa/{qaId}/favorite")
    public ApiResponse<CoursePersonalService.Favorite> favorite(@PathVariable long qaId,HttpServletRequest request) throws IOException {
        noBody(request);return ApiResponse.ok(personal.favorite(qaId,true));
    }
    @DeleteMapping("/qa/{qaId}/favorite")
    public ApiResponse<CoursePersonalService.Favorite> unfavorite(@PathVariable long qaId,HttpServletRequest request) throws IOException {
        noBody(request);return ApiResponse.ok(personal.favorite(qaId,false));
    }
    public static final class Feedback {
        @JsonProperty @JsonDeserialize(using=AccountRequests.Text.class) private String rating;
        @JsonProperty @JsonDeserialize(using=AccountRequests.Text.class) private String reason="";
        @JsonSetter(value="reason",nulls=Nulls.FAIL) public void setReason(String value) {reason=value;}
    }
    @PostMapping("/qa/{qaId}/feedback")
    public ApiResponse<CoursePersonalService.Feedback> feedback(@PathVariable long qaId,@RequestBody Feedback body,HttpServletRequest request) {
        CourseAuthController.query(request,Set.of());return ApiResponse.ok(personal.feedback(qaId,body.rating,body.reason));
    }
    @GetMapping("/admin/feedback") @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PageResult<CourseAdministrationService.AdminFeedback>> feedbackList(@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="10") long pageSize,
            @RequestParam(required=false) String rating,@RequestParam(required=false) String from,@RequestParam(required=false) String to,HttpServletRequest request) {
        CourseAuthController.query(request,Set.of("page","pageSize","rating","from","to"));return ApiResponse.ok(administration.feedback(page,pageSize,rating,from,to));
    }
    @GetMapping("/admin/stats") @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CourseAdministrationService.Stats> stats(@RequestParam(required=false) String from,@RequestParam(required=false) String to,HttpServletRequest request) {
        CourseAuthController.query(request,Set.of("from","to"));return ApiResponse.ok(administration.stats(from,to));
    }
    private void noBody(HttpServletRequest request) throws IOException {
        CourseAuthController.query(request,Set.of());if(request.getInputStream().read()!=-1) throw ApiException.invalid();
    }
}
