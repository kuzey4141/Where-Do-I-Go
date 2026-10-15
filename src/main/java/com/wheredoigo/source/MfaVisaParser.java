package com.wheredoigo.source;

import com.wheredoigo.country.Country;
import com.wheredoigo.entryrule.EntryRule;
import com.wheredoigo.entryrule.VisaStatus;
import com.wheredoigo.passport.PassportType;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.springframework.stereotype.Component;

@Component
public class MfaVisaParser {
    private static final Pattern FREE = Pattern.compile("vizeden muaf|vize muafiyeti uygulan");
    private static final Pattern REQUIRED = Pattern.compile("vizeye tabi");
    private static final Pattern DURATION = Pattern.compile(
            "\\b(?:\\d+(?:\\s*\\([^)]*\\))?|bir|iki|uc|alti)\\s*(?:gun(?:luk|de|e|u)?|hafta(?:lik|ya|yi)?|ay(?:lik|da|i|a)?|yil(?:lik|da|i|a)?)\\b(?:\\s+kadar)?");
    private static final Pattern SUBJECT = Pattern.compile(
            "^(?:(?:umuma mahsus|hususi|hizmet|diplomatik|resmi|pasaport(?:u|lari)?|ve)\\b[\\s,()]*)+$");
    private static final Pattern COMPLEX = Pattern.compile(
            "\\byas|\\bheyet|\\bgorev|\\bsart|\\bkosul|\\bhalinde|\\bolan\\b|\\bolmayan\\b|"
            + "\\bbiyometrik|\\bikamet izni|\\bgecerli.*\\bvize|\\bseyahat yetkilendirme|"
            + "\\beta\\b|\\bk-eta\\b|\\bsigorta|\\baskiya|\\bistisna|\\bancak\\b|"
            + "\\bdegil|\\bgecerlilik|\\buygulanmam|\\bmuaf tutulmam|\\bmuaf olm|"
            + "\\bresmi ziyaret|\\bresmi seyahat|\\byalnizca|\\bsadece");
    private static final Map<String, String> ALIASES = aliases();

    public ParseResult parse(String html, List<Country> countries) {
        Element content = Jsoup.parse(html).selectFirst(".mfa-content-box");
        if (content == null) {
            throw new IllegalArgumentException("Dışişleri sayfasının beklenen içerik alanı bulunamadı.");
        }
        Map<String, Country> byName = new HashMap<>();
        Map<String, Country> byCode = new HashMap<>();
        for (Country country : countries) {
            byName.put(normalize(country.name()), country);
            byCode.put(country.isoCode(), country);
        }
        Map<String, String> sections = extractSections(content);
        List<EntryRule> rules = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();
        for (var section : sections.entrySet()) {
            String name = normalize(section.getKey());
            if (name.equals("aciklamalar")) {
                continue;
            }
            Country country = byCode.get(ALIASES.get(name));
            if (country == null) {
                country = byName.get(name);
            }
            if (country == null || country.isoCode().equals("TR")) {
                unmatched.add(section.getKey());
                continue;
            }
            if (!seenCodes.add(country.isoCode())) {
                throw new IllegalArgumentException("Aynı ülke iki başlıkla eşleşti: " + country.isoCode());
            }
            rules.addAll(parseCountry(country, section.getValue()));
        }
        return new ParseResult(List.copyOf(rules), List.copyOf(unmatched), seenCodes.size());
    }

    private Map<String, String> extractSections(Element content) {
        Map<Element, String> headings = new HashMap<>();
        for (Element element : content.select("b, strong")) {
            String title = element.text().strip();
            if (title.endsWith(":") && title.length() < 150
                    && !normalize(title).contains("vize yukumlulukleri")
                    && !normalize(title).contains("vize uygulamalari")
                    && !normalize(title).contains("vizesi hakkinda")) {
                headings.put(element, title.substring(0, title.length() - 1).strip());
            }
        }
        Map<String, StringBuilder> sections = new LinkedHashMap<>();
        NodeTraversor.traverse(new NodeVisitor() {
            private StringBuilder current;

            @Override
            public void head(Node node, int depth) {
                if (node instanceof Element element && headings.containsKey(element)) {
                    String title = headings.get(element);
                    if (sections.containsKey(title)) {
                        throw new IllegalArgumentException("Tekrarlanan ülke başlığı: " + title);
                    }
                    current = new StringBuilder();
                    sections.put(title, current);
                } else if (current != null && node instanceof TextNode text) {
                    boolean insideHeading = false;
                    for (Node parent = text.parent(); parent != null; parent = parent.parent()) {
                        if (headings.containsKey(parent)) {
                            insideHeading = true;
                            break;
                        }
                    }
                    if (!insideHeading) {
                        current.append(text.getWholeText());
                    }
                } else if (current != null && node instanceof Element element && element.tagName().equals("br")) {
                    current.append('\n');
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (current != null && node instanceof Element element && element.isBlock()) {
                    current.append('\n');
                }
            }
        }, content);
        Map<String, String> result = new LinkedHashMap<>();
        sections.forEach((name, text) -> result.put(name, text.toString()
                .replace('\u00a0', ' ').replaceAll("\\s+", " ").strip()));
        return result;
    }

    private List<EntryRule> parseCountry(Country country, String text) {
        Map<PassportType, List<Candidate>> candidates = new EnumMap<>(PassportType.class);
        Set<PassportType> ambiguous = EnumSet.noneOf(PassportType.class);
        for (String sentence : text.split("(?<=[.!?])\\s+")) {
            String normalized = normalize(sentence);
            Set<PassportType> types = passportTypes(normalized);
            boolean policyMention = FREE.matcher(normalized).find() || REQUIRED.matcher(normalized).find()
                    || normalized.contains("vize") || normalized.contains("eta")
                    || normalized.contains("yetkilendirme") || normalized.contains("sigorta");
            if (!policyMention) {
                continue;
            }
            if (types.isEmpty()) {
                // An unscoped policy may qualify any earlier passport-specific sentence.
                ambiguous.addAll(EnumSet.allOf(PassportType.class));
                continue;
            }
            Candidate candidate = classify(sentence, normalized);
            if (candidate == null) {
                ambiguous.addAll(types);
            } else {
                types.forEach(type -> candidates.computeIfAbsent(type, key -> new ArrayList<>()).add(candidate));
            }
        }
        // Extra authorizations/insurance are never silently presented as unconditional visa-free entry.
        if (normalize(text).matches("(?s).*\\b(?:eta|k-eta|sigorta|yetkilendirme|askiya).*")) {
            ambiguous.addAll(EnumSet.allOf(PassportType.class));
        }
        List<EntryRule> result = new ArrayList<>();
        for (PassportType type : PassportType.values()) {
            List<Candidate> choices = candidates.getOrDefault(type, List.of());
            Set<VisaStatus> statuses = new HashSet<>();
            Set<String> durations = new HashSet<>();
            choices.forEach(choice -> {
                statuses.add(choice.status());
                if (choice.stayLimit() != null) {
                    durations.add(choice.stayLimit());
                }
            });
            boolean understood = !ambiguous.contains(type) && statuses.size() == 1 && durations.size() <= 1;
            VisaStatus status = understood ? statuses.iterator().next() : VisaStatus.UNKNOWN;
            String stayLimit = understood && durations.size() == 1 ? durations.iterator().next() : null;
            String conditions = status == VisaStatus.UNKNOWN
                    ? "Otomatik ayrıştırma kesin sonuç üretemedi. Kaynak açıklaması: " + text : text;
            result.add(new EntryRule(country, type, status, stayLimit, conditions, null, null));
        }
        return result;
    }

    private Candidate classify(String sentence, String normalized) {
        int firstPassport = normalized.indexOf("pasaport");
        if (firstPassport < 0 || !SUBJECT.matcher(normalized.substring(0, firstPassport).strip()).matches()
                || COMPLEX.matcher(normalized).find() || normalized.contains("transit")) {
            return null;
        }
        long freeCount = FREE.matcher(normalized).results().count();
        long requiredCount = REQUIRED.matcher(normalized).results().count();
        if (freeCount + requiredCount != 1) {
            return null;
        }
        boolean electronic = normalized.contains("e-vize") || normalized.contains("elektronik vize");
        boolean arrival = normalized.contains("sinir kapilar") || normalized.contains("varista");
        if (electronic && arrival) {
            return null;
        }
        VisaStatus status = freeCount == 1 ? VisaStatus.VISA_FREE
                : electronic ? VisaStatus.E_VISA : arrival ? VisaStatus.VISA_ON_ARRIVAL : VisaStatus.VISA_REQUIRED;
        if (freeCount == 1 && (electronic || arrival)) {
            return null;
        }
        Matcher duration = DURATION.matcher(normalized);
        int start = -1;
        int end = -1;
        int count = 0;
        while (duration.find()) {
            if (start < 0) {
                start = duration.start();
            }
            end = duration.end();
            count++;
        }
        // Keep the complete bounded phrase (e.g. 180 gün içinde 90 gün), not just its first number.
        if (count > 2 || (count > 0 && (status == VisaStatus.VISA_REQUIRED || normalized.contains("gecerli")))) {
            return null;
        }
        String stayLimit = start < 0 ? null : sentence.substring(start, end);
        return new Candidate(status, stayLimit);
    }

    private Set<PassportType> passportTypes(String text) {
        Set<PassportType> types = EnumSet.noneOf(PassportType.class);
        if (text.contains("umuma mahsus")) types.add(PassportType.ORDINARY);
        if (Pattern.compile("\\bhususi\\b").matcher(text).find()) types.add(PassportType.SPECIAL);
        if (Pattern.compile("\\bhizmet\\b").matcher(text).find()) types.add(PassportType.SERVICE);
        if (Pattern.compile("\\bdiplomatik\\b").matcher(text).find()) types.add(PassportType.DIPLOMATIC);
        if (Pattern.compile("\\bresmi\\s+(?:\\([^)]*\\)\\s*)?pasaport").matcher(text).find()) {
            types.addAll(EnumSet.of(PassportType.SPECIAL, PassportType.SERVICE, PassportType.DIPLOMATIC));
        }
        return types;
    }

    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replace('ı', 'i')
                .replace('\u00a0', ' ').replaceAll("\\s+", " ").strip();
    }

    private static Map<String, String> aliases() {
        Map<String, String> result = new HashMap<>();
        String[][] names = {
            {"Almanya Federal Cumhuriyeti", "DE"}, {"Amerika Birleşik Devletleri", "US"},
            {"Antigua-Barbuda", "AG"}, {"Bağımsız Samoa", "WS"}, {"Bosna-Hersek", "BA"},
            {"Cabo Verde", "CV"}, {"Çek Cumhuriyeti", "CZ"}, {"Çin Halk Cumhuriyeti", "CN"},
            {"Doğu Timor", "TL"}, {"Gine Bissau", "GW"}, {"Güney Afrika Cumhuriyeti", "ZA"},
            {"Ekvator", "EC"}, {"Fildişi Sahili", "CI"}, {"Filistin", "PS"},
            {"Hong Kong Özel İdare Bölgesi", "HK"}, {"İngiltere", "GB"},
            {"Kıbrıs Rum Kesimi (GKRY)", "CY"}, {"Komorlar Birliği", "KM"},
            {"Kongo", "CG"}, {"Kongo Demokratik Cumhuriyeti", "CD"},
            {"Kore Cumhuriyeti (Güney Kore)", "KR"}, {"Kore Demokratik Halk Cumhuriyeti (Kuzey)", "KP"},
            {"Lesotho", "LS"}, {"Makao Özel İdare Bölgesi", "MO"}, {"Marşal Adaları", "MH"},
            {"Lihtenştayn", "LI"}, {"Trinidad-Tobago", "TT"}, {"Venezuella", "VE"},
            {"Myanmar (Burma veya Birmanya)", "MM"}, {"Oman (Umman)", "OM"},
            {"Palau Cumhuriyeti", "PW"}, {"Rusya Federasyonu", "RU"},
            {"Sao Tome ve Principe", "ST"}, {"St. Christopher (St. Kitts) ve Nevis", "KN"},
            {"St. Lucia", "LC"}, {"St. Vincent-Grenadines", "VC"}, {"Solomon Adaları", "SB"}
        };
        for (String[] pair : names) result.put(normalize(pair[0]), pair[1]);
        return Map.copyOf(result);
    }

    private record Candidate(VisaStatus status, String stayLimit) {}

    public record ParseResult(List<EntryRule> rules, List<String> unmatchedCountries, int countryCount) {}
}
