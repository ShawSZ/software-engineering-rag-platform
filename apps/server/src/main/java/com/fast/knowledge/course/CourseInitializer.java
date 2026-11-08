package com.fast.knowledge.course;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;

@Component
public class CourseInitializer implements CommandLineRunner {
    private final CourseAccountService accounts;
    private final Environment environment;
    public CourseInitializer(CourseAccountService accounts, Environment environment) {
        this.accounts = accounts; this.environment = environment;
    }
    @Override
    public void run(String... args) {
        String username = environment.getProperty("APP_ADMIN_USERNAME", "");
        String password = environment.getProperty("APP_ADMIN_PASSWORD", "");
        boolean created = accounts.initializeAdmin(username, password);
        if (!created && (username.isEmpty() || password.isEmpty())) {
            LoggerFactory.getLogger(getClass()).info("未执行管理员初始化；已有管理员保持不变，首次初始化需本地环境配置");
        }
    }
}
