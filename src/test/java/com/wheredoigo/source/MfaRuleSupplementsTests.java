package com.wheredoigo.source;

import com.wheredoigo.country.Country;
import com.wheredoigo.entryrule.EntryRule;
import com.wheredoigo.entryrule.VisaStatus;
import com.wheredoigo.passport.PassportType;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MfaRuleSupplementsTests {
    private final MfaRuleSupplements supplements = new MfaRuleSupplements();
    private final LocalDate checkedOn = LocalDate.of(2026, 10, 28);

    @Test
    void fillsOnlyTheMissingDurationAndLinksItsActualOfficialEvidence() {
        var original = entry("PK", PassportType.SPECIAL, VisaStatus.VISA_FREE, null, "Pasaport hamilleri vizeden muaftır.");
        var result = supplements.apply(List.of(original), checkedOn).getFirst();
        assertEquals("Bir yıl içinde en fazla 90 gün", result.stayLimit());
        assertTrue(result.sourceUrl().contains("pakistan-seyahat"));
        assertEquals(checkedOn, result.verifiedAt());
        assertTrue(result.conditions().contains(original.conditions()));
        assertTrue(result.conditions().contains(original.sourceUrl()));
    }

    @Test
    void newerMainSourceInformationIsNotOverwritten() {
        var duration = entry("PK", PassportType.SPECIAL, VisaStatus.VISA_FREE, "Yeni kaynakta 60 gün", "vizeden muaftır");
        var status = entry("PK", PassportType.SPECIAL, VisaStatus.VISA_REQUIRED, null, "Yeni açıklamada vizeye tabidir");
        var results = supplements.apply(List.of(duration, status), checkedOn);
        assertSame(duration, results.get(0));
        assertSame(status, results.get(1));
    }

    @Test
    void evidenceDoesNotLeakToAnotherPassportCountryOrChangedParagraph() {
        var ordinary = entry("PK", PassportType.ORDINARY, VisaStatus.VISA_FREE, null, "vizeden muaftır");
        var otherCountry = entry("AA", PassportType.SPECIAL, VisaStatus.VISA_FREE, null, "vizeden muaftır");
        var changed = entry("PK", PassportType.SPECIAL, VisaStatus.VISA_FREE, null, "Kaynak metin değişti.");
        var results = supplements.apply(List.of(ordinary, otherCountry, changed), checkedOn);
        assertSame(ordinary, results.get(0));
        assertSame(otherCountry, results.get(1));
        assertSame(changed, results.get(2));
    }

    @Test
    void staleOrFutureEvidenceIsNotAppliedAndItsDateIsNotRefreshedByImport() {
        var original = entry("PK", PassportType.SPECIAL, VisaStatus.VISA_FREE, null, "vizeden muaftır");
        assertSame(original, supplements.apply(List.of(original), checkedOn.minusDays(1)).getFirst());
        assertSame(original, supplements.apply(List.of(original), checkedOn.plusDays(91)).getFirst());
        assertEquals(checkedOn, supplements.apply(List.of(original), checkedOn.plusDays(20)).getFirst().verifiedAt());
    }

    @Test
    void neverAddsAnExcludedOrAbsentDestination() {
        assertTrue(supplements.apply(List.of(), checkedOn).isEmpty());
        var original = entry("AA", PassportType.ORDINARY, VisaStatus.UNKNOWN, null, "Eksik açıklama");
        assertEquals(List.of(original), supplements.apply(List.of(original), checkedOn));
    }

    @Test
    void aSimilarParagraphFromADifferentSourceIsNotMatchedToTheMfaEvidence() {
        var original = new EntryRule(new Country("PK", "Test Ülkesi"), PassportType.SPECIAL,
                VisaStatus.VISA_FREE, null, "vizeden muaftır", "https://example.test/custom", checkedOn);
        assertSame(original, supplements.apply(List.of(original), checkedOn).getFirst());
    }

    @Test
    void separatesKenyanOfficialPassportExemptionFromTheOrdinaryPassportEtaRequirement() {
        var special = entry("KE", PassportType.SPECIAL, VisaStatus.UNKNOWN, null, "90 gün süreyle eTA sisteminden muaftır.");
        var ordinary = entry("KE", PassportType.ORDINARY, VisaStatus.UNKNOWN, null, "eTA ve zorunlu seyahat sigortası gereklidir.");
        var results = supplements.apply(List.of(special, ordinary), checkedOn);
        assertEquals(VisaStatus.VISA_FREE, results.get(0).visaStatus());
        assertEquals("90 güne kadar", results.get(0).stayLimit());
        assertEquals(VisaStatus.CONDITIONAL, results.get(1).visaStatus());
        assertNull(results.get(1).stayLimit());
        assertTrue(results.get(1).conditions().contains("sabit bir kalış süresi garantisi değildir"));
    }

    @Test
    void recordsPalauAsAnActualArrivalVisaWithAnInitialThirtyDayStay() {
        var original = entry("PW", PassportType.ORDINARY, VisaStatus.VISA_FREE, null, "vizeden muaftır");
        var result = supplements.apply(List.of(original), checkedOn).getFirst();
        assertEquals(VisaStatus.VISA_ON_ARRIVAL, result.visaStatus());
        assertTrue(result.stayLimit().startsWith("Girişte 30 gün"));
        assertTrue(result.conditions().contains("uzatma otomatik değildir"));
    }

    private EntryRule entry(String iso, PassportType passport, VisaStatus status, String stay, String conditions) {
        return new EntryRule(new Country(iso, "Test Ülkesi"), passport, status, stay, conditions,
                MfaVisaPageClient.SOURCE_URL, status == VisaStatus.UNKNOWN ? null : checkedOn);
    }
}
