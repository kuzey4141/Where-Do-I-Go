package com.wheredoigo.source;

import com.wheredoigo.catalog.TravelCatalogRepository;
import com.wheredoigo.entryrule.EntryRule;
import com.wheredoigo.entryrule.VisaStatus;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MfaImportService {
    private final MfaVisaPageClient client;
    private final MfaVisaParser parser;
    private final TravelCatalogRepository catalog;
    private final MfaEntryRuleWriter writer;
    private final int minimumCountries;

    public MfaImportService(MfaVisaPageClient client, MfaVisaParser parser,
            TravelCatalogRepository catalog, MfaEntryRuleWriter writer,
            @Value("${sources.mfa.minimum-countries:100}") int minimumCountries) {
        this.client = client;
        this.parser = parser;
        this.catalog = catalog;
        this.writer = writer;
        this.minimumCountries = minimumCountries;
    }

    public ImportResult importRules() throws IOException, InterruptedException {
        String html = client.fetchHtml();
        Instant fetchedAt = Instant.now();
        var parsed = parser.parse(html, catalog.countries());
        if (parsed.countryCount() < minimumCountries) {
            throw new IllegalStateException("Beklenenden az ülke ayrıştırıldı: " + parsed.countryCount()
                    + ". Veritabanına yazılmadı.");
        }
        LocalDate checkedAt = fetchedAt.atZone(ZoneOffset.UTC).toLocalDate();
        List<EntryRule> rules = parsed.rules().stream().map(rule -> new EntryRule(
                rule.country(), rule.passportType(), rule.visaStatus(), rule.stayLimit(), rule.conditions(),
                client.sourceUrl().toString(), rule.visaStatus() == VisaStatus.UNKNOWN ? null : checkedAt)).toList();
        writer.save(rules, fetchedAt);
        Map<VisaStatus, Long> counts = rules.stream()
                .collect(Collectors.groupingBy(EntryRule::visaStatus, Collectors.counting()));
        return new ImportResult(parsed.countryCount(), rules.size(), counts, parsed.unmatchedCountries());
    }

    public record ImportResult(int countries, int records, Map<VisaStatus, Long> statuses,
            List<String> unmatchedCountries) {}
}
