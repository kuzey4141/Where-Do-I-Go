package com.wheredoigo.catalog;

import com.wheredoigo.country.Country;
import com.wheredoigo.entryrule.EntryRule;
import com.wheredoigo.passport.PassportType;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryTravelCatalogRepository implements TravelCatalogRepository {
    private final List<Country> countries;
    private final List<EntryRule> entryRules;

    public InMemoryTravelCatalogRepository() {
        Locale turkish = Locale.forLanguageTag("tr");
        countries = Arrays.stream(Locale.getISOCountries())
                .map(code -> new Country(code,
                        new Locale.Builder().setRegion(code).build().getDisplayCountry(turkish)))
                .sorted(Comparator.comparing(Country::isoCode))
                .toList();
        entryRules = countries.stream()
                .filter(country -> !country.isoCode().equals("TR"))
                .flatMap(country -> Arrays.stream(PassportType.values())
                        .map(type -> EntryRule.unverified(country, type)))
                .toList();
    }

    @Override
    public List<Country> countries() {
        return countries;
    }

    @Override
    public List<EntryRule> entryRules() {
        return entryRules;
    }
}
