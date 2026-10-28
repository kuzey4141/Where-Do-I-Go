package com.wheredoigo.source;

import com.wheredoigo.country.Country;
import com.wheredoigo.entryrule.EntryRule;
import com.wheredoigo.entryrule.VisaStatus;
import com.wheredoigo.passport.PassportType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {
            "Umuma mahsus pasaport hamilleri geçerli Schengen vizesiyle 30 gün vizeden muaftır.",
            "Umuma mahsus pasaport hamilleri geçerli Schengen vizesiyle elektronik vize alabilmektedirler."
    })
    void aForeignVisaEligibilityRequirementIsNotTreatedAsUnconditionalAccess(String sentence) {
        var result = parse(sentence);
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

    @ParameterizedTest
    @ValueSource(strings = {
            "Vatandaşlarımız vizelerini Atina temsilciliğinden temin edebilirler.",
            "Öğrenim Vizesi İçin Talep Edilen Belgeler",
            "Grup vizesi alarak seyahat edenlere iki adet belge verilmektedir.",
            "Vatandaşlarımız yeni tip kimlik kartlarıyla da vizesiz giriş yapabilmektedirler.",
            "Vatandaşlarımızın bu ülke üzerinden transit uçuşlarında transit vize alması gerekmektedir."
    })
    void ancillaryInformationDoesNotErasePassportSpecificRules(String ancillary) {
        var result = parse("Umuma mahsus pasaport hamilleri vizeye tabidir. "
                + "Hususi, hizmet ve diplomatik pasaport hamilleri 90 gün vizeden muaftır. " + ancillary);
        assertEquals(VisaStatus.VISA_REQUIRED, rule(result, "AA", PassportType.ORDINARY).visaStatus());
        for (var type : List.of(PassportType.SPECIAL, PassportType.SERVICE, PassportType.DIPLOMATIC)) {
            assertEquals(VisaStatus.VISA_FREE, rule(result, "AA", type).visaStatus());
            assertEquals("90 gün", rule(result, "AA", type).stayLimit());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Umuma mahsus pasaport hamilleri sınır kapılarında 30 gün süreli vize alabilmektedirler.",
            "Umuma mahsus pasaport hamilleri 30 gün süreli vizelerini sınır kapılarından alabilmektedirler.",
            "Umuma mahsus pasaport hamillerine havalimanında 30 gün süreli vize verilmektedir.",
            "Umuma mahsus pasaport hamillerine ülkeye girişlerinde 30 gün süreli giriş vizesi ücretsiz tatbik edilmektedir.",
            "Umuma mahsus pasaport hamilleri vizeye tabi olup, havaalanında 30 gün süreli vize alınabilmektedir."
    })
    void recognizesArrivalVisaWithoutRequiringAnExtraVisaRequiredPredicate(String sentence) {
        var result = parse(sentence);
        assertEquals(VisaStatus.VISA_ON_ARRIVAL, rule(result, "AA", PassportType.ORDINARY).visaStatus());
        assertEquals("30 gün", rule(result, "AA", PassportType.ORDINARY).stayLimit());
        assertEquals(VisaStatus.UNKNOWN, rule(result, "AA", PassportType.SPECIAL).visaStatus());
    }

    @Test
    void anExplicitElectronicVisaRefinesAnEarlierVisaRequirement() {
        var result = parse("Umuma mahsus pasaport hamilleri vizeye tabidir. "
                + "Umuma mahsus pasaport hamilleri elektronik vize alabilmektedirler.");
        assertEquals(VisaStatus.E_VISA, rule(result, "AA", PassportType.ORDINARY).visaStatus());
    }

    @Test
    void doesNotChooseBetweenElectronicAndArrivalAlternatives() {
        var result = parse("Umuma mahsus pasaport hamilleri elektronik vize veya sınır kapılarında vize alabilmektedirler.");
        assertEquals(VisaStatus.UNKNOWN, rule(result, "AA", PassportType.ORDINARY).visaStatus());
    }

    @Test
    void recognizesAnElectronicAlternativeEvenWhenTheVisaNounIsShared() {
        var result = parse("Umuma mahsus pasaport hamilleri vizeye tabidir. "
                + "Umuma mahsus pasaport hamilleri vizelerini varışta veya elektronik olarak alabilmektedirler.");
        assertEquals(VisaStatus.UNKNOWN, rule(result, "AA", PassportType.ORDINARY).visaStatus());
    }

    @Test
    void recognizesTravelWithAnElectronicVisaAsAnExplicitVisaMethod() {
        var result = parse("Hususi pasaport hamilleri vizeye tabidir. "
                + "Hususi pasaport hamilleri 21 gün ikamet süreli elektronik vizeyle seyahat edebilmektedirler.");
        assertEquals(VisaStatus.E_VISA, rule(result, "AA", PassportType.SPECIAL).visaStatus());
        assertEquals("21 gün", rule(result, "AA", PassportType.SPECIAL).stayLimit());
    }

    @Test
    void anUnscopedElectronicVisaMethodCannotBeAssignedToAllPassportTypes() {
        var result = parse("Umuma mahsus pasaport hamilleri vizeye tabidir. "
                + "Diplomatik pasaport hamilleri 30 gün vizeden muaftır. "
                + "Vatandaşlarımız alacakları e-vizeyle seyahat edilebilmektedirler.");
        assertEquals(VisaStatus.UNKNOWN, rule(result, "AA", PassportType.ORDINARY).visaStatus());
        assertEquals(VisaStatus.UNKNOWN, rule(result, "AA", PassportType.DIPLOMATIC).visaStatus());
    }

    @Test
    void aLaterDiscontinuationIsNotIgnoredBecauseItLacksAPassportSubject() {
        var result = parse("Umuma mahsus pasaport hamilleri varışta vize alabilmektedirler. "
                + "Varışta vize uygulaması sona ermiştir.");
        assertEquals(VisaStatus.UNKNOWN, rule(result, "AA", PassportType.ORDINARY).visaStatus());
    }

    @Test
    void priorApprovalIsNotPresentedAsUnconditionalArrivalAccess() {
        var result = parse("Umuma mahsus pasaport hamilleri ön onay alıp bu başvuruyla birlikte sınır kapılarında vize alabilmektedirler.");
        assertEquals(VisaStatus.UNKNOWN, rule(result, "AA", PassportType.ORDINARY).visaStatus());
    }

    @Test
    void doesNotTurnDiscontinuedArrivalVisasIntoAvailableVisas() {
        var result = parse("Umuma mahsus pasaport hamillerine varışta vize verilmemektedir.");
        assertEquals(VisaStatus.UNKNOWN, rule(result, "AA", PassportType.ORDINARY).visaStatus());
    }

    @Test
    void periodLimitIsNotMistakenForSpecialEligibility() {
        var result = parse("Umuma mahsus pasaport hamilleri 365 gün içinde 90 günü aşmamak şartıyla vizeden muaftır.");
        var ordinary = rule(result, "AA", PassportType.ORDINARY);
        assertEquals(VisaStatus.VISA_FREE, ordinary.visaStatus());
        assertEquals("365 gün içinde 90 günü", ordinary.stayLimit());
    }

    @Test
    void ordinaryDurationWordingIsNotMistakenForSpecialEligibility() {
        var result = parse("Umuma mahsus pasaport hamilleri 180 gün içinde 90 güne kadar olan turistik seyahatlerinde vizeden muaftır.");
        assertEquals(VisaStatus.VISA_FREE, rule(result, "AA", PassportType.ORDINARY).visaStatus());
        assertEquals("180 gün içinde 90 güne kadar", rule(result, "AA", PassportType.ORDINARY).stayLimit());
    }

    @Test
    void retainsPerVisitLimitTogetherWithThePeriodTotal() {
        var result = parse("Umuma mahsus pasaport hamilleri her 180 günde 90 günü aşmamak kaydıyla her ziyarette 30 gün süreyle vizeden muaftır.");
        assertEquals(VisaStatus.VISA_FREE, rule(result, "AA", PassportType.ORDINARY).visaStatus());
        assertEquals("180 günde 90 günü aşmamak kaydıyla her ziyarette 30 gün",
                rule(result, "AA", PassportType.ORDINARY).stayLimit());
    }

    @Test
    void extractsSpelledOutNumbersWithParenthesizedDigits() {
        var result = parse("Resmi pasaportlar (Diplomatik, Hizmet ve Hususi), her altı (6) ay içerisinde doksan (90) günü aşmamak kaydıyla vizeden muaftır.");
        for (var type : List.of(PassportType.SPECIAL, PassportType.SERVICE, PassportType.DIPLOMATIC)) {
            assertEquals(VisaStatus.VISA_FREE, rule(result, "AA", type).visaStatus());
            assertEquals("altı (6) ay içerisinde doksan (90) günü", rule(result, "AA", type).stayLimit());
        }
    }

    @Test
    void durationOffsetsReferToOriginalUnicodeText() {
        var result = parse("Umuma mahsus pasaport hamilleri u\u0308lkeye seyahatlerinde 30 gün vizeden muaftır.");
        assertEquals("30 gün", rule(result, "AA", PassportType.ORDINARY).stayLimit());
    }

    @Test
    void keepsInitialStayAndConditionalExtensionTogether() {
        var result = parse("Umuma mahsus, hususi, hizmet ve diplomatik pasaport hamilleri vizeden muaftır. "
                + "Ülkeye ilk girişte 30 gün kalış imkanı sağlanmakta, başvuru üzerine onay alınması halinde kalış süresi 90 güne kadar uzatılabilmektedir.");
        for (var entry : result.rules()) {
            assertEquals(VisaStatus.VISA_FREE, entry.visaStatus());
            assertEquals("30 gün kalış imkanı sağlanmakta, başvuru üzerine onay alınması halinde kalış süresi 90 güne kadar", entry.stayLimit());
        }
    }

    @Test
    void aVisaValidityDurationIsNotPresentedAsPermittedStay() {
        var result = parse("Umuma mahsus pasaport hamilleri 30 gün geçerli elektronik vize alabilmektedirler.");
        var ordinary = rule(result, "AA", PassportType.ORDINARY);
        assertEquals(VisaStatus.E_VISA, ordinary.visaStatus());
        assertEquals(null, ordinary.stayLimit());
    }

    @Test
    void excludesPassportValidityFromThePermittedStay() {
        var result = parse("Diplomatik pasaport hamilleri asgari 6 ay geçerli pasaport ile her 180 gün içinde 90 günü aşmamak kaydıyla vizeden muaftır.");
        assertEquals(VisaStatus.VISA_FREE, rule(result, "AA", PassportType.DIPLOMATIC).visaStatus());
        assertEquals("180 gün içinde 90 günü", rule(result, "AA", PassportType.DIPLOMATIC).stayLimit());
    }

    @Test
    void acceptsAnExtraMahsusWordInAnOtherwiseExplicitPassportSubject() {
        var result = parse("Umuma mahsus pasaport hamilleri vizeye tabidir. "
                + "Diplomatik, Hizmet ve Hususi Mahsus Pasaport hamilleri vizeye tabidir.");
        assertTrue(result.rules().stream().allMatch(entry -> entry.visaStatus() == VisaStatus.VISA_REQUIRED));
    }

    @Test
    void separatesExplicitPassportRulesWhenAFullStopIsMissing() {
        var result = parse("Hususi ve hizmet pasaportu hamilleri vizeye tabidir Diplomatik pasaport hamilleri vizeden muaftır.");
        assertEquals(VisaStatus.VISA_REQUIRED, rule(result, "AA", PassportType.SPECIAL).visaStatus());
        assertEquals(VisaStatus.VISA_REQUIRED, rule(result, "AA", PassportType.SERVICE).visaStatus());
        assertEquals(VisaStatus.VISA_FREE, rule(result, "AA", PassportType.DIPLOMATIC).visaStatus());
        assertEquals(VisaStatus.UNKNOWN, rule(result, "AA", PassportType.ORDINARY).visaStatus());
    }

    @Test
    void aTransitOnlyPolicyDoesNotEstablishNormalEntryRights() {
        var result = parse("Umuma mahsus pasaport hamilleri transit geçişlerinde 30 gün vizeden muaftır.");
        assertEquals(VisaStatus.UNKNOWN, rule(result, "AA", PassportType.ORDINARY).visaStatus());
    }

    @Test
    void anEntryRuleMentioningExitAndTransitIsStillAnEntryRule() {
        var result = parse("Umuma mahsus pasaport hamilleri giriş, çıkış, transit geçişlerinde otuz (30) günü geçmeyen seyahatlerinde vizeden muaftırlar.");
        assertEquals(VisaStatus.VISA_FREE, rule(result, "AA", PassportType.ORDINARY).visaStatus());
        assertEquals("otuz (30) günü", rule(result, "AA", PassportType.ORDINARY).stayLimit());
    }

    @Test
    void anUnscopedEligibilityRestrictionStillBlocksUnconditionalExemption() {
        var result = parse("Umuma mahsus pasaport hamilleri 30 gün vizeden muaftır. "
                + "Bu muafiyet sadece başka bir ülkenin geçerli vizesine sahip olanlar için geçerlidir.");
        assertEquals(VisaStatus.UNKNOWN, rule(result, "AA", PassportType.ORDINARY).visaStatus());
    }

    @Test
    void aTransitRestrictionOnAnExemptionIsNotDiscardedAsAnUnrelatedTransitRule() {
        var result = parse("Umuma mahsus pasaport hamilleri 30 gün vizeden muaftır. "
                + "Bu muafiyet yalnızca transit yolcular için geçerlidir.");
        assertEquals(VisaStatus.UNKNOWN, rule(result, "AA", PassportType.ORDINARY).visaStatus());
    }

    @Test
    void aMandatoryArrivalFormIsNotSilentlyDiscarded() {
        var result = parse("Umuma mahsus pasaport hamilleri 30 gün vizeden muaftır. "
                + "Vatandaşlarımız seyahat öncesinde varış formunu doldurmalıdır.");
        assertEquals(VisaStatus.UNKNOWN, rule(result, "AA", PassportType.ORDINARY).visaStatus());
    }

    @Test
    void scopedAuthorizationDoesNotInvalidateAnUnrelatedPassportRule() {
        var result = parse("Umuma mahsus pasaport hamilleri 30 gün vizeden muaftır. "
                + "Diplomatik pasaport hamilleri 90 gün vizeden muaftır. "
                + "Umuma mahsus pasaport hamilleri elektronik seyahat yetkilendirmesi almalıdır.");
        assertEquals(VisaStatus.UNKNOWN, rule(result, "AA", PassportType.ORDINARY).visaStatus());
        assertEquals(VisaStatus.VISA_FREE, rule(result, "AA", PassportType.DIPLOMATIC).visaStatus());
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
    void includesVaticanOnlyWhenTheSourceExplicitlyExtendsItsItalyRule() {
        List<Country> catalog = List.of(new Country("IT", "İtalya"), new Country("VA", "Vatikan"),
                new Country("SM", "San Marino"));
        String paragraph = "Umuma mahsus pasaport hamilleri vizeye tabidir. "
                + "Hususi, hizmet ve diplomatik pasaport hamilleri 180 gün içinde 90 gün vizeden muaftır.";
        var withoutReference = parser.parse("<div class='mfa-content-box'><b>İtalya:</b> "
                + paragraph + "</div>", catalog);
        assertEquals(1, withoutReference.countryCount());
        var withReference = parser.parse("<div class='mfa-content-box'><b>İtalya:</b> "
                + paragraph + " (Vatikan için de geçerlidir).</div>", catalog);
        assertEquals(2, withReference.countryCount());
        assertEquals(8, withReference.rules().size());
        assertEquals(VisaStatus.VISA_REQUIRED, rule(withReference, "VA", PassportType.ORDINARY).visaStatus());
        assertEquals("180 gün içinde 90 gün", rule(withReference, "VA", PassportType.SPECIAL).stayLimit());
        assertTrue(withReference.rules().stream().noneMatch(entry -> entry.country().isoCode().equals("SM")));
    }

    @Test
    void aDedicatedVaticanHeadingTakesPrecedenceOverAnItalyCrossReference() {
        var result = parser.parse("""
                <div class='mfa-content-box'><b>İtalya:</b>
                Umuma mahsus pasaport hamilleri vizeye tabidir. (Vatikan için de geçerlidir).
                <b>Vatikan:</b> Umuma mahsus pasaport hamilleri 10 gün vizeden muaftır.</div>
                """, List.of(new Country("IT", "İtalya"), new Country("VA", "Vatikan")));
        assertEquals(2, result.countryCount());
        assertEquals(8, result.rules().size());
        assertEquals(VisaStatus.VISA_FREE, rule(result, "VA", PassportType.ORDINARY).visaStatus());
        assertEquals("10 gün", rule(result, "VA", PassportType.ORDINARY).stayLimit());
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
