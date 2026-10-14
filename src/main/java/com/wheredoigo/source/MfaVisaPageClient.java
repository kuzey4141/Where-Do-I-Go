package com.wheredoigo.source;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MfaVisaPageClient {
    public static final String SOURCE_URL =
            "https://www.mfa.gov.tr/turk-vatandaslarinin-tabi-oldugu-vize-uygulamalari.tr.mfa";

    private final URI sourceUrl;
    private final HttpClient httpClient;

    public MfaVisaPageClient(@Value("${sources.mfa.visa-url:" + SOURCE_URL + "}") URI sourceUrl) {
        this.sourceUrl = sourceUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String fetchHtml() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(sourceUrl)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/html")
                .header("User-Agent", "WhereDoIGo/0.0.1")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Dışişleri sayfası alınamadı. HTTP durum kodu: " + response.statusCode());
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.toLowerCase(Locale.ROOT).startsWith("text/html")) {
            throw new IOException("Dışişleri sayfası HTML yerine farklı bir içerik döndürdü.");
        }
        if (response.body().isBlank()) {
            throw new IOException("Dışişleri sayfası boş içerik döndürdü.");
        }
        return response.body();
    }
}
