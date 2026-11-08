package com.fast.knowledge.config;

import com.fast.knowledge.mapper.UserMapper;
import com.fast.knowledge.model.entity.KbUser;
import com.fast.knowledge.service.SystemConfigService;
import com.fast.knowledge.service.WorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
public class DataInitializer implements CommandLineRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final WorkspaceService workspaceService;
    private final SystemConfigService systemConfigService;
    private final String initialPassword;

    public DataInitializer(UserMapper userMapper, PasswordEncoder passwordEncoder,
                           WorkspaceService workspaceService, SystemConfigService systemConfigService,
                           @Value("${knowledge.bootstrap.initial-password:}") String initialPassword) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.workspaceService = workspaceService;
        this.systemConfigService = systemConfigService;
        this.initialPassword = initialPassword;
    }

    @Override
    public void run(String... args) {
        if (userMapper.countAll() == 0) {
            KbUser admin = new KbUser();
            admin.setUsername("admin");
            String password = initialPassword.isBlank() ? UUID.randomUUID().toString() : initialPassword;
            admin.setPassword(passwordEncoder.encode(password));
            admin.setDisplayName("系统管理员");
            admin.setRole("ADMIN");
            admin.setStatus(1);
            admin.setMustChangePassword(1);
            admin.setAuthSource("LOCAL");
            userMapper.insert(admin);
            workspaceService.createDefaultForUser(admin.getId());
            log.info("已创建初始管理员账号，首次使用需完成密码设置");
        }
        if (workspaceService.getDefault() == null) {
            KbUser admin = userMapper.findByUsername("admin");
            if (admin != null) {
                workspaceService.createDefaultForUser(admin.getId());
            }
        }
        if (!systemConfigService.isSetupComplete()) {
            systemConfigService.setInstanceName(systemConfigService.getInstanceName());
        }
    }
}
