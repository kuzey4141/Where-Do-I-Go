package com.wheredoigo.catalog;

import com.wheredoigo.country.Country;
import com.wheredoigo.entryrule.EntryRule;
import com.wheredoigo.entryrule.VisaStatus;
import com.wheredoigo.passport.PassportType;
import com.wheredoigo.passport.PassportTypeResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TravelCatalogController {
    private final TravelCatalogService service;

    public TravelCatalogController(TravelCatalogService service) {
        this.service = service;
    }

    @GetMapping("/passport-types")
    public List<PassportTypeResponse> passportTypes() {
        return service.passportTypes();
    }

    @GetMapping("/countries")
    public List<Country> countries() {
        return service.countries();
    }

    @GetMapping("/entry-rules")
    public List<EntryRule> entryRules(@RequestParam PassportType passportType,
            @RequestParam(required = false) VisaStatus visaStatus) {
        return service.entryRules(passportType, visaStatus);
    }

    @GetMapping("/countries/{isoCode}/entry-rules")
    public List<EntryRule> countryEntryRules(@PathVariable String isoCode) {
        return service.countryEntryRules(isoCode);
    }
}
