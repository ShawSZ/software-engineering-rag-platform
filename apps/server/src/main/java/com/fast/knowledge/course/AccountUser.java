package com.fast.knowledge.course;

import com.fast.knowledge.model.entity.KbUser;
import java.time.ZoneId;

/** The only account representation exposed by course APIs. */
public record AccountUser(Long userId, String username, String displayName, String role, String status, String createdAt) {
    public static AccountUser from(KbUser user) {
        return new AccountUser(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(),
                Integer.valueOf(1).equals(user.getStatus()) ? "ENABLED" : "DISABLED",
                user.getCreatedAt().atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString());
    }
}
