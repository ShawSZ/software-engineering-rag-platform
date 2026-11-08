package com.fast.knowledge.course;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class CourseInitializerTest {
    @Test void missingConfigurationAllowsStartupAndNeverInventsDefaultCredentials() {
        var accounts=mock(CourseAccountService.class);
        assertDoesNotThrow(()->new CourseInitializer(accounts,new MockEnvironment()).run());
        verify(accounts).initializeAdmin("","");
    }
    @Test void suppliedPasswordIsNeverLogged(CapturedOutput output) {
        var accounts=mock(CourseAccountService.class);
        String password="Fixture_"+java.util.UUID.randomUUID();
        var env=new MockEnvironment().withProperty("APP_ADMIN_USERNAME","fixture_admin").withProperty("APP_ADMIN_PASSWORD",password);
        when(accounts.initializeAdmin("fixture_admin",password)).thenReturn(true);
        new CourseInitializer(accounts,env).run();verify(accounts).initializeAdmin("fixture_admin",password);
        assertFalse(output.getAll().contains(password));
    }
}
