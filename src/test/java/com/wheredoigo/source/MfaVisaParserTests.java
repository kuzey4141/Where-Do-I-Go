package com.wheredoigo.source;

import com.wheredoigo.country.Country;
import com.wheredoigo.entryrule.EntryRule;
import com.wheredoigo.entryrule.VisaStatus;
import com.wheredoigo.passport.PassportType;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MfaVisaParserTests {
    private final MfaVisaParser parser = new MfaVisaParser();
    private final Country country = new Country("AA", "Test Ülkesi");

    @Test
    void separatesCountriesAndAssignsGroupedPassportTypes() {
        String html = """
                <div class="mfa-content-box"><p>
                <b><span>Test Ülkesi:</span></b> Umuma Mahsus Pasaport hamilleri vizeye tabidir.<br>
                Hususi, Hizmet ve Diplomatik Pasaport hamilleri 180 gün içinde 90 günü aşmamak kaydıyla vizeden muaftır.<br>
                <b><span>İkinci Ülke:</span></b> Umuma mahsus, hususî, hizmet ve diplomatik pasaport hamilleri 30 gün süreyle vizeden muaftır.
                </p></div>
                """;
        var result = parser.parse(html, List.of(country, new Country("BB", "İkinci Ülke")));

        assertEquals(2, result.countryCount());
        assertEquals(8, result.rules().size());
        assertEquals(VisaStatus.VISA_REQUIRED, rule(result, "AA", PassportType.ORDINARY).visaStatus());
        for (PassportType type : List.of(PassportType.SPECIAL, PassportType.SERVICE, PassportType.DIPLOMATIC)) {
            EntryRule rule = rule(result, "AA", type);
            assertEquals(VisaStatus.VISA_FREE, rule.visaStatus());
            assertEquals("180 gün içinde 90 günü", rule.stayLimit());
        }
        assertEquals("30 gün", rule(result, "BB", PassportType.ORDINARY).stayLimit());
    }

    @Test
    void understandsOfficialPassportGrouping() {
        var result = parse("Umuma mahsus ve resmi pasaport hamilleri 90 gün süreyle vizeden muaftır.");
        assertTrue(result.rules().stream().allMatch(rule -> rule.visaStatus() == VisaStatus.VISA_FREE));
    }

    @Test
    void doesNotFlattenDelegationConditionsIntoVisaFreeAccess() {
        var result = parse("Umuma mahsus pasaport hamilleri vizeye tabidir. "
                + "Resmi heyet mensubu olan diplomatik, hizmet ve hususi pasaport hamilleri vizeden muaftır. "
                + "Resmi heyet mensubu olmayan diplomatik, hizmet ve hususi pasaport hamilleri vizeye tabidir.");
        assertEquals(VisaStatus.VISA_REQUIRED, rule(result, "AA", PassportType.ORDINARY).visaStatus());
        assertEquals(VisaStatus.UNKNOWN, rule(result, "AA", PassportType.SPECIAL).visaStatus());
        assertTrue(rule(result, "AA", PassportType.SPECIAL).conditions().contains("heyet"));
    }

    @Test
    void doesNotTurnAgeRestrictionsIntoUnconditionalVisaFreeAccess() {
        var result = parse("15 yaşından küçük umuma mahsus pasaport hamilleri 30 gün vizeden muaftır. "
                + "15-50 yaş arasındaki umuma mahsus pasaport hamilleri vizeye tabidir.");
        assertEquals(VisaStatus.UNKNOWN, rule(result, "AA", PassportType.ORDINARY).visaStatus());
    }

    @Test
    void doesNotTurnOfficialVisitExemptionsIntoTouristExemptions() {
        var result = parse("Hizmet pasaportu hamilleri resmi ziyaretlerinde vizeden muaftır.");
        assertEquals(VisaStatus.UNKNOWN, rule(result, "AA", PassportType.SERVICE).visaStatus());
    }

    @Test
    void doesNotTreatNegatedExemptionsAsVisaFree() {
        var result = parse("Umuma mahsus pasaport hamilleri vizeden muaf değildir.");
        assertEquals(VisaStatus.UNKNOWN, rule(result, "AA", PassportType.ORDINARY).visaStatus());
    }

    @Test
    void doesNotDiscardAdditionalTravelAuthorizationRequirements() {
        var result = parse("Umuma mahsus, hususi, hizmet ve diplomatik pasaport hamilleri 90 gün vizeden muaftır. "
                + "Vatandaşlarımızın elektronik seyahat yetkilendirmesi alması gerekmektedir.");
        assertTrue(result.rules().stream().allMatch(rule -> rule.visaStatus() == VisaStatus.UNKNOWN));
    }

    @Test
    void refusesConflictingRulesForTheSamePassport() {
        var result = parse("Umuma mahsus pasaport hamilleri vizeye tabidir. "
                + "Umuma mahsus pasaport hamilleri vizeden muaftır.");
        assertEquals(VisaStatus.UNKNOWN, rule(result, "AA", PassportType.ORDINARY).visaStatus());
    }

    @Test
    void recognizesSimpleElectronicAndArrivalRules() {
        var result = parse("Umuma mahsus pasaport hamilleri vizeye tabi olup e-vize alabilmektedir. "
                + "Hususi pasaport hamilleri vizeye tabi olup vizelerini sınır kapılarında alabilmektedir.");
        assertEquals(VisaStatus.E_VISA, rule(result, "AA", PassportType.ORDINARY).visaStatus());
        assertEquals(VisaStatus.VISA_ON_ARRIVAL, rule(result, "AA", PassportType.SPECIAL).visaStatus());
    }

    @Test
    void reportsUnmatchedCountriesWithoutMixingTheirTextIntoNeighbors() {
        var result = parser.parse("""
                <div class="mfa-content-box">
                <b>Test Ülkesi:</b> Umuma mahsus pasaport hamilleri vizeye tabidir.<br>
                <b>Bilinmeyen Ülke:</b> Umuma mahsus pasaport hamilleri vizeden muaftır.
                </div>
                """, List.of(country));
        assertEquals(List.of("Bilinmeyen Ülke"), result.unmatchedCountries());
        assertEquals(VisaStatus.VISA_REQUIRED, rule(result, "AA", PassportType.ORDINARY).visaStatus());
    }

    @Test
    void matchesExplicitCountryAliases() {
        var result = parser.parse("""
                <div class="mfa-content-box"><b>Almanya Federal Cumhuriyeti:</b>
                Umuma mahsus pasaport hamilleri vizeye tabidir.</div>
                """, List.of(new Country("DE", "Almanya")));
        assertEquals(1, result.countryCount());
        assertEquals("DE", result.rules().getFirst().country().isoCode());
    }

    @Test
    void refusesUnexpectedHtmlAndDuplicateCountrySections() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("<html>Blocked</html>", List.of(country)));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                <div class="mfa-content-box"><b>Test Ülkesi:</b> X <b>Test Ülkesi:</b> Y</div>
                """, List.of(country)));
    }

    private MfaVisaParser.ParseResult parse(String explanation) {
        return parser.parse("<div class='mfa-content-box'><b>Test Ülkesi:</b> "
                + explanation + "</div>", List.of(country));
    }

    private EntryRule rule(MfaVisaParser.ParseResult result, String code, PassportType type) {
        return result.rules().stream().filter(rule -> rule.country().isoCode().equals(code)
                && rule.passportType() == type).findFirst().orElseThrow();
    }
}
