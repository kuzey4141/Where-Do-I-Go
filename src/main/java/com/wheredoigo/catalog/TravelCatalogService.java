package com.wheredoigo.catalog;

import com.wheredoigo.country.Country;
import com.wheredoigo.entryrule.EntryRule;
import com.wheredoigo.entryrule.VisaStatus;
import com.wheredoigo.passport.PassportType;
import com.wheredoigo.passport.PassportTypeResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TravelCatalogService {
    private final TravelCatalogRepository repository;

    public TravelCatalogService(TravelCatalogRepository repository) {
        this.repository = repository;
    }

    public List<PassportTypeResponse> passportTypes() {
        return Arrays.stream(PassportType.values()).map(PassportTypeResponse::from).toList();
    }

    public List<Country> countries() {
        return repository.countries();
    }

    public List<EntryRule> entryRules(PassportType passportType, VisaStatus visaStatus) {
        return repository.entryRules().stream()
                .filter(rule -> rule.passportType() == passportType)
                .filter(rule -> visaStatus == null || rule.visaStatus() == visaStatus)
                .toList();
    }

    public List<EntryRule> countryEntryRules(String isoCode) {
        String normalizedCode = isoCode.toUpperCase(Locale.ROOT);
        if (!normalizedCode.matches("[A-Z]{2}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ISO kodu iki harften oluşmalıdır. Örnek: DE.");
        }
        Country country = repository.countries().stream()
                .filter(candidate -> candidate.isoCode().equals(normalizedCode))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ülke bulunamadı: " + normalizedCode));
        return repository.entryRules().stream()
                .filter(rule -> rule.country().isoCode().equals(country.isoCode()))
                .toList();
    }
}
