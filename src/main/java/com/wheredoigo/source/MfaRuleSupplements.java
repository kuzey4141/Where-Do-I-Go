package com.wheredoigo.source;

import com.wheredoigo.entryrule.EntryRule;
import com.wheredoigo.entryrule.VisaStatus;
import com.wheredoigo.passport.PassportType;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** Reviewed official evidence for information absent from the main MFA paragraph. */
public final class MfaRuleSupplements {
    private final List<Supplement> supplements;

    public MfaRuleSupplements() {
        try (InputStream input = getClass().getResourceAsStream("/data/entry-rule-supplements.json")) {
            if (input == null) throw new IllegalStateException("Ek resmî kaynak verisi bulunamadı.");
            supplements = List.of(JsonMapper.builder().build().readValue(input, Supplement[].class));
            for (Supplement supplement : supplements) {
                URI source = URI.create(supplement.sourceUrl());
                if (!"https".equals(source.getScheme()) || supplement.checkedOn() == null
                        || supplement.note() == null || supplement.note().isBlank()) {
                    throw new IllegalStateException("Ek kaynak kaydı geçersiz: " + supplement.countryIsoCodes());
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Ek resmî kaynak verisi okunamadı.", exception);
        }
    }

    public List<EntryRule> apply(List<EntryRule> rules, LocalDate importedOn) {
        // Map existing rows only: supplements never restore an excluded destination.
        return rules.stream().map(rule -> supplement(rule, importedOn)).toList();
    }

    private EntryRule supplement(EntryRule rule, LocalDate importedOn) {
        if (!MfaVisaPageClient.SOURCE_URL.equals(rule.sourceUrl())) return rule;
        for (Supplement supplement : supplements) {
            if (!supplement.countryIsoCodes().contains(rule.country().isoCode())
                    || !supplement.passportTypes().contains(rule.passportType())
                    || supplement.expectedStatus() != rule.visaStatus()
                    || rule.stayLimit() != null
                    || importedOn.isBefore(supplement.checkedOn())
                    || importedOn.isAfter(supplement.checkedOn().plusDays(90))
                    || !MfaVisaParser.normalize(rule.conditions() == null ? "" : rule.conditions())
                        .contains(MfaVisaParser.normalize(supplement.requiredSourceText()))) {
                continue;
            }
            String original = rule.conditions() == null ? "" : rule.conditions()
                    .replaceFirst("^Kalış süresi kaynakta belirtilmemiştir; bu durum süresiz kalış hakkı vermez\\.\\s*", "")
                    .replaceFirst("^(?:Otomatik ayrıştırma kesin sonuç üretemedi\\.|Kaynak açıklamasında bu pasaport türüne ait bir kural bulunamadı\\.) Kaynak açıklaması: ", "");
            String conditions = "Ek resmî kaynak kontrolü (" + supplement.checkedOn() + "): "
                    + supplement.note() + " Kaynak: " + supplement.sourceUrl()
                    + "\n\nDışişleri açıklaması: " + original + "\nKaynak: " + rule.sourceUrl();
            return new EntryRule(rule.country(), rule.passportType(), supplement.status(),
                    supplement.stayLimit(), conditions, supplement.sourceUrl(), supplement.checkedOn());
        }
        // A new MFA duration or changed visa status always takes precedence over old supplemental evidence.
        return rule;
    }

    public record Supplement(List<String> countryIsoCodes, List<PassportType> passportTypes,
            VisaStatus expectedStatus, VisaStatus status, String stayLimit, String sourceUrl,
            LocalDate checkedOn, String requiredSourceText, String note) {}
}
