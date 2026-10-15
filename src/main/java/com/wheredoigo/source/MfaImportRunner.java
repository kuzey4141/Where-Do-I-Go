package com.wheredoigo.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "sources.mfa.import-on-startup", havingValue = "true")
public class MfaImportRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MfaImportRunner.class);
    private final MfaImportService service;

    public MfaImportRunner(MfaImportService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var result = service.importRules();
        log.info("Dışişleri aktarımı tamamlandı: {} ülke, {} kayıt. Durumlar: {}",
                result.countries(), result.records(), result.statuses());
        if (!result.unmatchedCountries().isEmpty()) {
            log.warn("Ülke kataloğuyla eşleştirilemeyen başlıklar: {}", result.unmatchedCountries());
        }
    }
}
