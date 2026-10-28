package com.wheredoigo.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TravelCatalogApiTests {
    @Autowired
    private MockMvc mvc;

    @Test
    void listsFourPassportTypes() throws Exception {
        mvc.perform(get("/api/passport-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].code").value("ORDINARY"))
                .andExpect(jsonPath("$[0].color").value("Bordo"))
                .andExpect(jsonPath("$[*].code", hasItem("DIPLOMATIC")));
    }

    @Test
    void listsIsoCountriesWithTurkishNames() throws Exception {
        mvc.perform(get("/api/countries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].isoCode", hasItem("DE")))
                .andExpect(jsonPath("$[*].name", hasItem("Almanya")));
    }

    @Test
    void exposesTurkishSummariesInCountryListAndNestedEntryRules() throws Exception {
        mvc.perform(get("/api/countries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].summary", everyItem(not(emptyOrNullString()))))
                .andExpect(jsonPath("$[?(@.isoCode == 'DE')].summary", hasItem(containsString("Almanya"))))
                .andExpect(jsonPath("$[?(@.isoCode == 'TR')].summary", hasItem(containsString("Anadolu"))));

        mvc.perform(get("/api/countries/DE/entry-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[*].country.summary", everyItem(containsString("Almanya"))));
    }

    @Test
    void listsSelectedAdditionalDestinations() throws Exception {
        mvc.perform(get("/api/countries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].isoCode", hasItem("SM")))
                .andExpect(jsonPath("$[*].isoCode", hasItem("VA")))
                .andExpect(jsonPath("$[*].isoCode", hasItem("GL")))
                .andExpect(jsonPath("$[*].isoCode", hasItem("PR")))
                .andExpect(jsonPath("$[*].isoCode", hasItem("GI")));
        mvc.perform(get("/api/countries/SM/entry-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void filtersRulesByPassportAndKeepsUnverifiedDataUnknown() throws Exception {
        mvc.perform(get("/api/entry-rules").param("passportType", "ORDINARY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].passportType").value("ORDINARY"))
                .andExpect(jsonPath("$[*].passportType", everyItem(is("ORDINARY"))))
                .andExpect(jsonPath("$[*].visaStatus", everyItem(is("UNKNOWN"))))
                .andExpect(jsonPath("$[0].sourceUrl").isEmpty())
                .andExpect(jsonPath("$[0].verifiedAt").isEmpty());
    }

    @Test
    void doesNotTreatUnknownRulesAsVisaFree() throws Exception {
        mvc.perform(get("/api/entry-rules")
                        .param("passportType", "ORDINARY").param("visaStatus", "VISA_FREE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listsAllPassportsForCountryAndAcceptsLowercaseIsoCode() throws Exception {
        mvc.perform(get("/api/countries/de/entry-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[*].country.isoCode", everyItem(is("DE"))))
                .andExpect(jsonPath("$[*].passportType", hasItem("SERVICE")));
    }

    @Test
    void excludesDomesticTravelFromEntryRules() throws Exception {
        mvc.perform(get("/api/countries/TR/entry-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void rejectsMissingOrInvalidQueryParameters() throws Exception {
        mvc.perform(get("/api/entry-rules"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Zorunlu parametre eksik: passportType"));
        mvc.perform(get("/api/entry-rules").param("passportType", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        mvc.perform(get("/api/entry-rules")
                        .param("passportType", "ORDINARY").param("visaStatus", "INVALID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void distinguishesMalformedIsoCodesFromMissingCountries() throws Exception {
        mvc.perform(get("/api/countries/DEU/entry-rules"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/countries/ZZ/entry-rules"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Ülke bulunamadı: ZZ"));
    }
}
