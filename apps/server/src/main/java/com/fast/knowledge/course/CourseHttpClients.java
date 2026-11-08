package com.fast.knowledge.course;

import javax.net.ssl.*;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Locale;

/** Uses the platform's existing trusted roots without modifying any certificate store. */
final class CourseHttpClients {
    private CourseHttpClients() { }
    static HttpClient create() {
        var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NEVER);
        if(System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows")
                && System.getProperty("javax.net.ssl.trustStore")==null) {
            try {
                var roots=KeyStore.getInstance("Windows-ROOT");roots.load(null,null);
                builder.sslContext(trust(roots));
            } catch(Exception e) {
                org.slf4j.LoggerFactory.getLogger(CourseHttpClients.class).warn("System trusted roots unavailable; using JVM certificate validation");
            }
        }
        return builder.build();
    }
    static SSLContext trust(KeyStore roots) throws java.security.GeneralSecurityException {
        var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(roots);
        var context=SSLContext.getInstance("TLS");context.init(null,factory.getTrustManagers(),null);return context;
    }
}
