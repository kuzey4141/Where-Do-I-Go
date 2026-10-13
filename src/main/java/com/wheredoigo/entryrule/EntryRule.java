package com.wheredoigo.entryrule;

import com.wheredoigo.country.Country;
import com.wheredoigo.passport.PassportType;
import java.time.LocalDate;

public record EntryRule(
        Country country,
        PassportType passportType,
        VisaStatus visaStatus,
        String stayLimit,
        String conditions,
        String sourceUrl,
        LocalDate verifiedAt) {

    public static EntryRule unverified(Country country, PassportType passportType) {
        return new EntryRule(country, passportType, VisaStatus.UNKNOWN, null,
                "Bu ülke ve pasaport türü için giriş koşulları henüz doğrulanmadı.",
                null, null);
    }
}
