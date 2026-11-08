package com.fast.knowledge.integration.support;

import com.fast.knowledge.KnowledgeApplication;
import com.fast.knowledge.security.TokenBlacklistService;
import org.springframework.boot.SpringApplication;

/** Forked only by PersistentLogoutTest, against its private temporary H2 database. */
public final class PersistentLogoutProbe {
    public static void main(String[] args) {
        com.fast.knowledge.testguard.CourseTestDatabaseGuardFactory.verifyFileArguments(args);
        try (var context = SpringApplication.run(KnowledgeApplication.class, args)) {
            var blacklist = context.getBean(TokenBlacklistService.class);
            String token = System.getenv("B02_TEST_TOKEN");
            if ("write".equals(System.getenv("B02_TEST_MODE"))) {
                blacklist.blacklist(token);
                // Deliberately skip shutdown hooks and H2 close: no flush may be borrowed from graceful shutdown.
                Runtime.getRuntime().halt(0);
            }
            if (!blacklist.isBlacklisted(token)) throw new IllegalStateException("Persisted revocation was lost");
            if ("read-write".equals(System.getenv("B02_TEST_MODE"))) {
                blacklist.blacklist(System.getenv("B02_TEST_SECOND_TOKEN"));
                Runtime.getRuntime().halt(0); // Also prove durability after the schema is already migrated.
            }
        }
    }
}
