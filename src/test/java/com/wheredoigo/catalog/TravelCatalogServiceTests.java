package com.wheredoigo.catalog;

import com.wheredoigo.country.Country;
import com.wheredoigo.entryrule.EntryRule;
import com.wheredoigo.entryrule.VisaStatus;
import com.wheredoigo.passport.PassportType;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TravelCatalogServiceTests {
    @Test
    void combinesPassportAndVisaFiltersWithMixedRules() {
        // Synthetic fixtures exercise filtering; they are not actual visa information.
        Country first = new Country("AA", "Test A");
        Country second = new Country("BB", "Test B");
        EntryRule matching = new EntryRule(first, PassportType.ORDINARY, VisaStatus.VISA_FREE,
                null, "Test fixture", null, null);
        List<EntryRule> rules = List.of(matching,
                new EntryRule(first, PassportType.SPECIAL, VisaStatus.VISA_FREE,
                        null, "Test fixture", null, null),
                new EntryRule(second, PassportType.ORDINARY, VisaStatus.VISA_REQUIRED,
                        null, "Test fixture", null, null),
                EntryRule.unverified(second, PassportType.ORDINARY));
        TravelCatalogRepository repository = new TravelCatalogRepository() {
            public List<Country> countries() {
                return List.of(first, second);
            }

            public List<EntryRule> entryRules() {
                return rules;
            }
        };
        TravelCatalogService service = new TravelCatalogService(repository);

        assertEquals(List.of(matching), service.entryRules(PassportType.ORDINARY, VisaStatus.VISA_FREE));
        assertEquals(3, service.entryRules(PassportType.ORDINARY, null).size());
        assertEquals(List.of(rules.get(3)), service.entryRules(PassportType.ORDINARY, VisaStatus.UNKNOWN));
    }
}
