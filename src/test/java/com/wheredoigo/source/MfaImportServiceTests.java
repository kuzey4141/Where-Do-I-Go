package com.wheredoigo.source;

import com.wheredoigo.catalog.TravelCatalogRepository;
import com.wheredoigo.country.Country;
import com.wheredoigo.entryrule.VisaStatus;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MfaImportServiceTests {
    @Test
    void rejectsTruncatedPagesBeforeWritingAnything() throws Exception {
        MfaVisaPageClient client = mock(MfaVisaPageClient.class);
        TravelCatalogRepository catalog = mock(TravelCatalogRepository.class);
        MfaEntryRuleWriter writer = mock(MfaEntryRuleWriter.class);
        when(client.fetchHtml()).thenReturn("<div class='mfa-content-box'>Incomplete page</div>");
        when(catalog.countries()).thenReturn(List.of(new Country("DE", "Almanya")));
        MfaImportService service = new MfaImportService(client, new MfaVisaParser(), catalog, writer, 100);

        assertThrows(IllegalStateException.class, service::importRules);
        verifyNoInteractions(writer);
    }

    @Test
    void attachesSourceAndFetchMetadataWithoutVerifyingUnknownRules() throws Exception {
        MfaVisaPageClient client = mock(MfaVisaPageClient.class);
        TravelCatalogRepository catalog = mock(TravelCatalogRepository.class);
        MfaEntryRuleWriter writer = mock(MfaEntryRuleWriter.class);
        when(client.sourceUrl()).thenReturn(URI.create("https://example.test/visa"));
        when(client.fetchHtml()).thenReturn("""
                <div class='mfa-content-box'><b>Almanya:</b>
                Umuma mahsus pasaport hamilleri vizeye tabidir.</div>
                """);
        when(catalog.countries()).thenReturn(List.of(new Country("DE", "Almanya")));
        MfaImportService service = new MfaImportService(client, new MfaVisaParser(), catalog, writer, 1);

        var result = service.importRules();
        assertEquals(4, result.records());
        assertEquals(3L, result.statuses().get(VisaStatus.UNKNOWN));
        verify(writer).save(argThat(rules -> rules.stream().allMatch(rule ->
                rule.sourceUrl().equals("https://example.test/visa")
                && (rule.visaStatus() == VisaStatus.UNKNOWN
                        ? rule.verifiedAt() == null : rule.verifiedAt() != null))), any());
    }
}
