package com.wheredoigo.catalog;

import com.wheredoigo.country.Country;
import com.wheredoigo.entryrule.EntryRule;
import java.util.List;

public interface TravelCatalogRepository {
    List<Country> countries();

    List<EntryRule> entryRules();
}
