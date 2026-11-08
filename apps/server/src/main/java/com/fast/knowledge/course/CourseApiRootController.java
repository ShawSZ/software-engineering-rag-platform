package com.fast.knowledge.course;
import com.fast.knowledge.common.ApiException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
/** Prevent Spring's welcome-page mapping from resolving the API root as index.html. */
@RestController
public class CourseApiRootController {
    @RequestMapping({"", "/"})
    public void root() { throw ApiException.notFound(); }
}
