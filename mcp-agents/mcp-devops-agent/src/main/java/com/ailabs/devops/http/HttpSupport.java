package com.ailabs.devops.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Small helpers around {@link java.net.http.HttpClient} shared by every integration. */
public final class HttpSupport {

    public static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ERROR_BODY = 600;

    private HttpSupport() {}

    public record Resp(int status, String body, HttpHeaders headers) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public JsonNode json() {
            try {
                return JSON.readTree(body == null || body.isBlank() ? "{}" : body);
            } catch (IOException e) {
                throw new IllegalStateException("Expected JSON but got: " + truncate(body, 200), e);
            }
        }
    }

    /**
     * @param insecure trust any certificate (lab use only)
     * @param caPem    PEM (or base64 of PEM, as found in kubeconfig) of a CA to trust, or null
     * @param cookies  keep cookies between calls (Jenkins ties CSRF crumbs to a session)
     */
    public static HttpClient client(boolean insecure, String caPem, boolean cookies, Duration connectTimeout) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(connectTimeout != null ? connectTimeout : Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (insecure) {
            b.sslContext(trustAll());
        } else if (caPem != null && !caPem.isBlank()) {
            b.sslContext(trusting(caPem));
        }
        if (cookies) {
            b.cookieHandler(new CookieManager());
        }
        return b.build();
    }

    public static Resp send(HttpClient client, HttpRequest request) {
        try {
            HttpResponse<String> r = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body(), r.headers());
        } catch (IOException e) {
            throw new IllegalStateException(request.method() + " " + request.uri() + " failed: " + describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling " + request.uri(), e);
        }
    }

    static String describe(IOException e) {
        if (e instanceof java.net.ConnectException) {
            return "connection refused or host unreachable";
        }
        if (e instanceof java.net.http.HttpTimeoutException) {
            return "timed out";
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return "TLS error (" + e.getMessage() + "); set the CA certificate or, for labs, skip TLS verification";
        }
        if (e instanceof java.net.UnknownHostException) {
            return "unknown host " + e.getMessage();
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    public static IllegalStateException failure(String what, Resp r) {
        return new IllegalStateException(what + " failed: HTTP " + r.status() + " " + truncate(r.body(), MAX_ERROR_BODY));
    }

    public static String basicAuth(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** URL-encode one path segment or query value. */
    public static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    static SSLContext trusting(String caPem) {
        try {
            String pem = caPem.contains("BEGIN CERTIFICATE")
                    ? caPem
                    : new String(Base64.getMimeDecoder().decode(caPem), StandardCharsets.US_ASCII);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int i = 0;
            for (Certificate c : cf.generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)))) {
                ks.setCertificateEntry("ca-" + i++, c);
            }
            if (i == 0) {
                throw new IllegalArgumentException("no certificates found in CA");
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load CA certificate: " + e.getMessage(), e);
        }
    }

    static SSLContext trustAll() {
        try {
            TrustManager tm = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {tm}, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
