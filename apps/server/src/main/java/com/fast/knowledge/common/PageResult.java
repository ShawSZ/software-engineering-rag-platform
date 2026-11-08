package com.fast.knowledge.common;
import java.util.List;
public record PageResult<T>(List<T> items, long total, long page, long pageSize) {
    public static void validate(long page, long pageSize) {
        if (page < 1 || page > Integer.MAX_VALUE || pageSize < 1 || pageSize > 50) throw ApiException.invalid();
    }
}
