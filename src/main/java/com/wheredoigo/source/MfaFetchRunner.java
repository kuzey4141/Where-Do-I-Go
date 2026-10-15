package com.wheredoigo.source;

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
        log.info("Dışişleri sayfası alındı: {} karakter.", html.length());
    }
}
