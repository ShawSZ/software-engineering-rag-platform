package com.fast.knowledge.config;

import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;
import java.util.Set;

/** Optional upstream adapters remain available as source, outside the course startup graph. */
public class CourseComponentFilter implements TypeFilter {
    private static final Set<String> SHARED = Set.of(
            "config.KnowledgeProperties", "config.AppConfig", "config.MybatisPlusConfig",
            "config.MybatisMetaObjectHandler", "config.SecurityConfig", "config.RootStaticConfig",
            "config.BundleWebConfig", "config.ProductionConfigValidator",
            "cache.CaffeineCacheProvider", "storage.LocalStorageProvider",
            "security.JwtUtil", "security.TokenBlacklistService", "security.RateLimitInterceptor",
            "security.SecurityProblemHandler");
    @Override
    public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
        String name = reader.getClassMetadata().getClassName();
        String prefix = "com.fast.knowledge.";
        if (!name.startsWith(prefix)) return false;
        String relative = name.substring(prefix.length());
        return !(relative.equals("KnowledgeApplication") || relative.startsWith("common.")
                || relative.startsWith("course.") || SHARED.contains(relative));
    }
}
