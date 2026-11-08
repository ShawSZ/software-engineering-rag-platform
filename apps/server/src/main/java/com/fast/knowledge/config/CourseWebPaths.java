package com.fast.knowledge.config;

/** Share the same boundary between Tomcat static handling and the API dispatcher. */
public final class CourseWebPaths {
    private CourseWebPaths() { }
    public static boolean isApi(String path) { return path.equals("/api") || path.startsWith("/api/"); }
    public static boolean isSpaRoute(String path) {
        // Keep in sync with the course router; obsolete and unknown paths remain 404.
        return path.equals("/")
                || path.matches("/(login|register|documents|qa|study|history|favorites|403)/?")
                || path.matches("/(documents|history)/[0-9]+/?")
                || path.matches("/admin/(documents|categories|users|feedback|stats)/?");
    }
}
