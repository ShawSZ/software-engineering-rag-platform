package com.fast.knowledge.course;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CourseHttpClientsTest {
    @TempDir Path temporary;HttpsServer server;KeyStore roots;
    @BeforeEach void setup() throws Exception {
        String password=UUID.randomUUID().toString();Path store=temporary.resolve("fixture.p12"),log=temporary.resolve("keytool.log");
        String executable=System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")?"keytool.exe":"keytool";
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048",
                "-validity","1","-dname","CN=localhost","-ext","SAN=dns:localhost","-storetype","PKCS12","-keystore",store.toString(),"-storepass",password)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {assertTrue(process.waitFor(20,TimeUnit.SECONDS));assertEquals(0,process.exitValue());}
        finally {if(process.isAlive())process.destroyForcibly();}
        var keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(store)){keys.load(input,password.toCharArray());}
        var managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());managers.init(keys,password.toCharArray());
        var context=SSLContext.getInstance("TLS");context.init(managers.getKeyManagers(),null,null);
        roots=KeyStore.getInstance(KeyStore.getDefaultType());roots.load(null,null);roots.setCertificateEntry("fixture",keys.getCertificate("fixture"));
        server=HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/",exchange->{byte[] body="OK".getBytes(java.nio.charset.StandardCharsets.US_ASCII);exchange.sendResponseHeaders(200,body.length);try(var output=exchange.getResponseBody()){output.write(body);}});server.start();
    }
    @AfterEach void close(){if(server!=null)server.stop(0);}
    HttpRequest request(String host){return HttpRequest.newBuilder(URI.create("https://"+host+":"+server.getAddress().getPort()+"/")).timeout(Duration.ofSeconds(3)).GET().build();}
    @Test void explicitlyTrustedChainWithCorrectHostnameIsAccepted() throws Exception {
        try(var client=HttpClient.newBuilder().sslContext(CourseHttpClients.trust(roots)).build()){
            assertEquals(200,client.send(request("localhost"),HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }
    @Test void platformClientStillRejectsAnUntrustedCertificate() {
        try(var client=CourseHttpClients.create()){
            assertThrows(SSLHandshakeException.class,()->client.send(request("localhost"),HttpResponse.BodyHandlers.discarding()));
        }
    }
    @Test void trustedCertificateWithWrongHostnameIsRejected() throws Exception {
        try(var client=HttpClient.newBuilder().sslContext(CourseHttpClients.trust(roots)).build()){
            assertThrows(SSLHandshakeException.class,()->client.send(request("127.0.0.1"),HttpResponse.BodyHandlers.discarding()));
        }
    }
}
