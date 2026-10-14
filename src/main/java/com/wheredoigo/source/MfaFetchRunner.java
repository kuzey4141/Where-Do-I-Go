package com.wheredoigo.source;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "sources.mfa.fetch-on-startup", havingValue = "true")
public class MfaFetchRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MfaFetchRunner.class);
    private final MfaVisaPageClient client;

    public MfaFetchRunner(MfaVisaPageClient client) {
        this.client = client;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String html = client.fetchHtml();
        Path output = Path.of("target", "mfa-visa-page.html");
        Files.createDirectories(output.getParent());
        Files.writeString(output, html, StandardCharsets.UTF_8);
        log.info("Dışişleri sayfası indirildi: {} karakter. HTML dosyası: {}", html.length(), output);
    }
}
