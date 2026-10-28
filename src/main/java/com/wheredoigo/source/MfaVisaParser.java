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
    // Match the original text: normalization can change offsets (e.g. decomposed accents).
    private static final String NUMBER = "(?:\\d+|bir|iki|[üu][çc]|d[öo]rt|be[şs]|alt[ıi]|yedi|sekiz|dokuz|on|otuz|altm[ıi][şs]|doksan)";
    private static final Pattern DURATION = Pattern.compile(
            "\\b" + NUMBER + "(?:\\s+" + NUMBER + ")?(?:\\s*\\([^)]*\\))?\\s*"
                    + "(?:g[üu]n(?:l[üu]k|de|e|[üu])?|hafta(?:l[ıi]k|ya|y[ıi])?|ay(?:l[ıi]k|da|[ıi]|a)?|y[ıi]l(?:l[ıi]k|da|[ıi]|a)?)\\b(?:\\s+kadar)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SUBJECT = Pattern.compile(
            "^(?:(?:umuma mahsus|hususi|hizmet|diplomatik|resmi|mahsus|pasaport(?:u|lari)?|ve)\\b[\\s,()]*)+$");
    private static final Pattern COMPLEX = Pattern.compile(
            "\\byas|\\bheyet|\\bgorev|\\bsart|\\bkosul|\\bhalinde|\\bolan\\b|\\bolmayan\\b|"
            + "\\bbiyometrik|\\bikamet izni|\\bvize\\p{L}*\\s+(?:hamili|sahibi|sahip)|"
            + "\\bgecerli.*\\bvize(?:si|sine|sinin|siyle|leri|lerini|lerine|leriyle|yle)?\\b|\\bseyahat yetkilendirme|"
            + "\\beta\\b|\\bk-eta\\b|\\bsigorta|\\baskiya|\\bistisna|\\bancak\\b|"
            + "\\bdegil|\\bgecerlilik|\\buygulanmam|\\bmuaf tutulmam|\\bmuaf olm|"
            + "\\bresmi ziyaret|\\bresmi seyahat|\\byalnizca|\\bsadece|\\bbasvuruyla birlikte|\\bon onay|\\bonceden onay");
    private static final Map<String, String> ALIASES = aliases();
    private static final Pattern ELECTRONIC = Pattern.compile("\\be-vize|\\belektronik vize|\\belektronik olarak");
    private static final Pattern ARRIVAL = Pattern.compile("\\bsinir kapilar|\\bvaris(?:ta|inda|larinda)|\\bhava(?:liman|alan)\\p{L}*|\\bulkeye giris(?:te|inde|lerinde)");
    private static final Pattern OBTAIN_VISA = Pattern.compile(
            "\\bvize\\p{L}*.*\\b(?:alabil|alinabil|alinmak|veril|tatbik|almalar|almasi|alarak)|"
                    + "(?:\\be-vize|\\belektronik vize).*\\b(?:seyahat edebil|seyahat edilebil|girebil)");
    private static final Pattern EXTRA_REQUIREMENT = Pattern.compile(
            "\\b(?:eta|k-eta)\\b|\\b(?:sigorta|yetkilendirme|askiya)\\p{L}*|\\b(?:form|kart).*\\b(?:doldur|basvur)");
    private static final Pattern NEGATED = Pattern.compile(
            "\\bdegil|\\buygulanmam|\\bmuaf tutulmam|\\bmuaf olm|\\bsona er|\\bkaldiril|\\bverilmem|\\balinam|\\bverilme.*\\bmumkun degil");
    private static final Pattern QUALIFIED_EXEMPTION = Pattern.compile(
            "\\b(?:bu muafiyet|bu uygulama|bu imkan|muafiyetten).*\\b(?:yalnizca|sadece|halinde|kosul|sart|yararlan|gecerli)");
    private static final Pattern VISA_VALIDITY = Pattern.compile(
            "\\b(?:gun|ay|yil)\\p{L}*\\s+gecerli\\b(?!\\s+pasaport)|\\bvize\\p{L}*\\s+gecerlilik");
    private static final Pattern NORMAL_ENTRY = Pattern.compile("\\bgiris|\\bcikis|\\bturistik|\\bturizm");

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
        String vaticanExplanation = null;
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
            if (country.isoCode().equals("IT")
                    && normalize(section.getValue()).contains("vatikan icin de gecerlidir")) {
                vaticanExplanation = section.getValue();
            }
        }
        // The source explicitly extends its Italy paragraph to Vatican City.
        // Never infer a dependent destination solely from geography or overwrite its own heading.
        if (vaticanExplanation != null && byCode.containsKey("VA") && seenCodes.add("VA")) {
            rules.addAll(parseCountry(byCode.get("VA"), vaticanExplanation));
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
        Set<PassportType> previousTypes = Set.of();
        // A missing full stop between two explicit passport rules occurs in the source.
        String separated = text.replaceAll("(?iu)(tabidir)\\s+(?=(?:diplomatik|hususi|hizmet|umuma)\\b)", "$1. ");
        for (String sentence : separated.split("(?<=[.!?])\\s+")) {
            String normalized = normalize(sentence);
            Set<PassportType> types = passportTypes(normalized);
            if ((transitOnly(normalized) && !QUALIFIED_EXEMPTION.matcher(normalized).find())
                    || (types.isEmpty() && normalized.contains("kimlik")
                    && !EXTRA_REQUIREMENT.matcher(normalized).find())) {
                continue;
            }
            if (EXTRA_REQUIREMENT.matcher(normalized).find()) {
                ambiguous.addAll(types.isEmpty() ? EnumSet.allOf(PassportType.class) : types);
                continue;
            }
            boolean policyMention = FREE.matcher(normalized).find() || REQUIRED.matcher(normalized).find()
                    || QUALIFIED_EXEMPTION.matcher(normalized).find()
                    || ((ELECTRONIC.matcher(normalized).find() || ARRIVAL.matcher(normalized).find())
                        && (OBTAIN_VISA.matcher(normalized).find() || NEGATED.matcher(normalized).find()));
            if (!policyMention) {
                // Only bind an explicit initial-stay explanation to the preceding passport rule.
                // Preserve extension conditions in the text rather than presenting its upper bound as the initial stay.
                if (types.isEmpty() && normalized.contains("ilk giris") && normalized.contains("kalis")
                        && !previousTypes.isEmpty()) {
                    String stay = stayLimit(sentence);
                    if (stay != null) {
                        for (PassportType type : previousTypes) {
                            var choices = candidates.getOrDefault(type, List.of());
                            if (!choices.isEmpty() && choices.stream().allMatch(candidate ->
                                    candidate.status() == VisaStatus.VISA_FREE && candidate.stayLimit() == null)) {
                                candidates.put(type, List.of(new Candidate(VisaStatus.VISA_FREE, stay)));
                            }
                        }
                    }
                }
                continue;
            }
            if (types.isEmpty()) {
                // An unscoped policy may qualify any earlier passport-specific sentence.
                ambiguous.addAll(EnumSet.allOf(PassportType.class));
                continue;
            }
            Candidate candidate = classify(sentence, normalized);
            previousTypes = Set.copyOf(types);
            if (candidate == null) {
                ambiguous.addAll(types);
            } else {
                types.forEach(type -> candidates.computeIfAbsent(type, key -> new ArrayList<>()).add(candidate));
            }
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
            // Obtaining an eVisa/arrival visa refines a visa requirement, not a visa exemption.
            if (!statuses.contains(VisaStatus.VISA_FREE)
                    && (statuses.contains(VisaStatus.E_VISA) || statuses.contains(VisaStatus.VISA_ON_ARRIVAL))) {
                statuses.remove(VisaStatus.VISA_REQUIRED);
            }
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
        // These phrases express a bounded stay, not eligibility such as age or an official delegation.
        String eligibility = normalized.replaceAll("\\b(?:asmamak|gecmemek)\\s+(?:sartiyla|kosuluyla|kaydiyla)\\b", "")
                .replaceAll("\\bkadar olan\\b", "kadar")
                .replaceAll("\\b(?:gun|ay|yil)\\p{L}*\\s+gecerli\\s+(?=(?:elektronik vize|e-vize))", "")
                .replaceAll("\\bgecerli\\s+pasaport\\b", "pasaport");
        int firstPassport = normalized.indexOf("pasaport");
        if (firstPassport < 0 || !SUBJECT.matcher(normalized.substring(0, firstPassport).strip()).matches()
                || COMPLEX.matcher(eligibility).find() || NEGATED.matcher(normalized).find()) {
            return null;
        }
        long freeCount = FREE.matcher(normalized).results().count();
        long requiredCount = REQUIRED.matcher(normalized).results().count();
        if (freeCount > 1 || requiredCount > 1 || (freeCount > 0 && requiredCount > 0)) {
            return null;
        }
        boolean obtainable = OBTAIN_VISA.matcher(normalized).find();
        boolean electronic = ELECTRONIC.matcher(normalized).find() && obtainable;
        boolean arrival = ARRIVAL.matcher(normalized).find() && obtainable;
        if (electronic && arrival) {
            return null;
        }
        VisaStatus status = freeCount == 1 ? VisaStatus.VISA_FREE
                : electronic ? VisaStatus.E_VISA : arrival ? VisaStatus.VISA_ON_ARRIVAL : VisaStatus.VISA_REQUIRED;
        if (freeCount == 1 && (electronic || arrival)) {
            return null;
        }
        if (freeCount + requiredCount == 0 && !electronic && !arrival) {
            return null;
        }
        // A visa's validity is not necessarily its permitted stay.
        String stay = status == VisaStatus.VISA_REQUIRED || VISA_VALIDITY.matcher(normalized).find()
                ? null : stayLimit(sentence);
        return new Candidate(status, stay);
    }

    private boolean transitOnly(String text) {
        return text.contains("transit") && !NORMAL_ENTRY.matcher(text).find();
    }

    private String stayLimit(String sentence) {
        Matcher duration = DURATION.matcher(sentence);
        int start = -1;
        int end = -1;
        int count = 0;
        while (duration.find()) {
            // Document validity is independent of the permitted stay in the destination.
            String following = normalize(sentence.substring(duration.end()));
            if (following.matches("^gecerli\\s+pasaport.*")) {
                continue;
            }
            if (start < 0) {
                start = duration.start();
            }
            end = duration.end();
            count++;
        }
        // Keep the complete bounded phrase (e.g. 180 gün içinde 90 gün), not just its first number.
        if (count > 3) {
            return null;
        }
        return start < 0 ? null : sentence.substring(start, end);
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
