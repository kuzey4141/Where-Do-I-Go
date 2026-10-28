package com.wheredoigo.source;

import com.wheredoigo.country.Country;
import com.wheredoigo.entryrule.EntryRule;
import com.wheredoigo.entryrule.VisaStatus;
import com.wheredoigo.passport.PassportType;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
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
import java.util.regex.MatchResult;
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
    private final Clock clock;
    private static final DateTimeFormatter TURKISH_DATE = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("d MMMM uuuu").toFormatter(Locale.forLanguageTag("tr"));
    private static final Pattern EFFECTIVE_FROM = Pattern.compile(
            "^(\\d{1,2}\\s+\\p{L}+\\s+\\d{4})\\s+tarihinden itibaren geçerli olmak üzere", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public MfaVisaParser() { this(Clock.systemUTC()); }

    MfaVisaParser(Clock clock) { this.clock = clock; }
    private static final Pattern FREE = Pattern.compile("vizeden muaf|vize muafiyeti (?:uygulan|suresi)");
    private static final Pattern REQUIRED = Pattern.compile("vizeye tabi");
    // Match the original text: normalization can change offsets (e.g. decomposed accents).
    private static final String NUMBER = "(?:\\d+|bir|iki|[üu][çc]|d[öo]rt|be[şs]|alt[ıi]|yedi|sekiz|dokuz|on|otuz|altm[ıi][şs]|doksan)";
    private static final Pattern DURATION = Pattern.compile(
            "\\b" + NUMBER + "(?:\\s+" + NUMBER + ")?(?:\\s*\\([^)]*\\))?\\s*"
                    + "(?:g[üu]n(?:l[üu]k|de|e|[üu]|d[üu]r)?|hafta(?:l[ıi]k|ya|y[ıi])?|ay(?:l[ıi]k|da|[ıi]|a)?|y[ıi]l(?:l[ıi]k|da|[ıi]|a)?)\\b(?:\\s+kadar)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SUBJECT = Pattern.compile(
            "^(?:(?:umuma mahsus|hususi|hizmet|diplomatik|resmi|mahsus|pasaport(?:u|lari)?|ve)\\b[\\s,()]*)+$");
    private static final Pattern COMPLEX = Pattern.compile(
            "\\byas|\\bheyet|\\bgorev|\\bsart|\\bkosul|\\bhalinde|\\bolan\\b|\\bolmayan\\b|"
            + "\\bbiyometrik|\\bikamet izni|\\bvize\\p{L}*\\s+(?:hamili|sahibi|sahip)|"
            + "\\bgecerli\\s+(?:(?:schengen|abd|ab|birlesik krallik|irlanda|kanada|avustralya)\\s+)*vize(?:si|sine|sinin|siyle|leri|lerini|lerine|leriyle|yle)?\\b|"
            + "\\baskiya|\\bistisna|\\bancak\\b|"
            + "\\bdegil|\\bgecerlilik|\\buygulanmam|\\bmuaf tutulmam|\\bmuaf olm(?:ayan|ad|az|am|uyor)|"
            + "\\bresmi ziyaret|\\bresmi seyahat|\\byalnizca|\\bsadece|\\bbasvuruyla birlikte|\\bon onay|\\bonceden onay");
    private static final Map<String, String> ALIASES = aliases();
    private static final Pattern ELECTRONIC = Pattern.compile("\\be-vize|\\belektronik vize|\\belektronik olarak|\\beta\\.gov\\.lk");
    private static final Pattern ARRIVAL = Pattern.compile("\\bsinir kapilar|\\bvaris(?:ta|inda|larinda)\\b|\\bhava(?:liman|alan)\\p{L}*(?:inda|nda)\\b|\\bulkeye giris(?:te|inde|lerinde)\\b");
    private static final Pattern OBTAIN_VISA = Pattern.compile(
            "\\bvize\\p{L}*.*\\b(?:alabil|alinabil|alinmak|veril|tatbik|almalar|almasi|alarak|ita edil)|"
                    + "(?:\\be-vize|\\belektronik vize).*\\b(?:seyahat edebil|seyahat edilebil|girebil)");
    private static final Pattern EXTRA_REQUIREMENT = Pattern.compile(
            "\\b(?:eta|k-eta)\\b|\\b(?:sigorta|yetkilendirme|askiya)\\p{L}*|\\b(?:form|kart).*\\b(?:doldur|basvur)");
    private static final Pattern NEGATED = Pattern.compile(
            "\\bdegil|\\buygulanmam|\\bmuaf tutulmam|\\bmuaf olm(?:ayan|ad|az|am|uyor)|\\bsona er|\\bkaldiril|\\bverilmem|\\balinam|\\bverilme.*\\bmumkun degil");
    private static final Pattern QUALIFIED_EXEMPTION = Pattern.compile(
            "\\b(?:bu muafiyet|bu uygulama|bu imkan|muafiyetten).*\\b(?:yalnizca|sadece|halinde|kosul|sart|yararlan|gecerli)");
    private static final Pattern VISA_VALIDITY = Pattern.compile(
            "\\b(?:gun|ay|yil)\\p{L}*\\s+gecerli\\b(?!\\s+pasaport)|\\bvize\\p{L}*\\s+gecerli(?:lik|lig)");
    private static final Pattern PERMITTED_STAY = Pattern.compile(
            "\\b(?:ikamet|kalis)\\s+sure|\\bkalma|\\bkalabil");
    private static final Pattern DOCUMENT_VALIDITY_LABEL = Pattern.compile(
            "\\b(?:(?:e-)?vize\\p{L}*|pasaport\\p{L}*|(?:ikamet|oturum|calisma)\\s+izn\\p{L}*)"
                    + "\\s+gecerli(?:lik|lig)\\p{L}*");
    private static final Pattern DOCUMENT_AFTER_DURATION = Pattern.compile(
            "^(?:sureli\\s+)?(?:pasaport\\p{L}*|(?:ikamet|oturum|calisma)\\s+izn\\p{L}*)\\b");
    private static final Pattern STAY_BEFORE_DURATION = Pattern.compile(
            "\\b(?:kalis|ikamet)\\s+sure\\p{L}*(?:\\s+(?:her|en|fazla|azami|ilk|toplam|toplamda|olarak))*[\\s:=\"“”]*$");
    private static final Pattern STAY_AFTER_DURATION = Pattern.compile(
            "^[\\s\"'“”‘’():-]*(?:(?:sureyle\\s+)?vizeden muaf|(?:ikamet|kalis)\\s+sure|kalma\\p{L}*|kalabil\\p{L}*|kalinabil\\p{L}*)");
    private static final Pattern PROCESSING_BEFORE_DURATION = Pattern.compile(
            "\\b(?:basvuru|islem|degerlendirme|sonuclanma)\\p{L}*"
                    + "(?:\\s+(?:sure\\p{L}*|ortalama|yaklasik|en|fazla|azami|genellikle))*[\\s:=]*$");
    private static final Pattern PROCESSING_AFTER_DURATION = Pattern.compile(
            "^(?:(?:icinde|icerisinde)\\s+)?(?:sonuclan|degerlendir|isleme alin|yanitlan)");
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
        List<String> generalPolicies = new ArrayList<>();
        Set<VisaStatus> discontinued = EnumSet.noneOf(VisaStatus.class);
        Set<PassportType> qualifiedExemptions = EnumSet.noneOf(PassportType.class);
        Set<PassportType> previousTypes = Set.of();
        // A missing full stop between two explicit passport rules occurs in the source.
        String separated = text.replaceAll("(?iu)(tabidir)\\s+(?=(?:diplomatik|hususi|hizmet|umuma)\\b)", "$1. ");
        for (String sentence : separated.split("(?<=[.!?])\\s+")) {
            Matcher effective = EFFECTIVE_FROM.matcher(sentence);
            if (effective.find()) {
                try {
                    if (LocalDate.parse(effective.group(1), TURKISH_DATE).isAfter(LocalDate.now(clock))) continue;
                } catch (DateTimeParseException exception) {
                    // A dated rule with an unreadable start date cannot establish today's entitlement.
                    continue;
                }
            }
            String normalized = normalize(sentence);
            Set<PassportType> types = passportTypes(normalized);
            if ((transitOnly(normalized) && !QUALIFIED_EXEMPTION.matcher(normalized).find())
                    || (types.isEmpty() && normalized.contains("kimlik")
                    && !EXTRA_REQUIREMENT.matcher(normalized).find())) {
                continue;
            }
            if (normalized.contains("askiya")) {
                ambiguous.addAll(types.isEmpty() ? EnumSet.allOf(PassportType.class) : types);
                continue;
            }
            if (QUALIFIED_EXEMPTION.matcher(normalized).find()
                    && (normalized.contains("muafiyet") || normalized.contains("bu uygulama"))) {
                qualifiedExemptions.addAll(types.isEmpty() ? EnumSet.allOf(PassportType.class) : types);
                continue;
            }
            if (types.isEmpty() && NEGATED.matcher(normalized).find()
                    && (ARRIVAL.matcher(normalized).find() || ELECTRONIC.matcher(normalized).find())) {
                if (ARRIVAL.matcher(normalized).find()) discontinued.add(VisaStatus.VISA_ON_ARRIVAL);
                if (ELECTRONIC.matcher(normalized).find()) discontinued.add(VisaStatus.E_VISA);
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
                generalPolicies.add(sentence);
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
        // A general visa-obtaining method refines the passports that need a visa.
        // It must not erase a separately stated exemption for another passport.
        for (String sentence : generalPolicies) {
            String normalized = normalize(sentence);
            Candidate candidate = classify(sentence, normalized, true);
            if (candidate == null) continue;
            boolean citizenSubject = normalized.contains("vatandaslarimiz")
                    || normalized.contains("turkiye cumhuriyeti vatandaslari");
            for (PassportType type : PassportType.values()) {
                List<Candidate> choices = candidates.getOrDefault(type, List.of());
                boolean requiresVisa = choices.stream().anyMatch(item -> item.status() == VisaStatus.VISA_REQUIRED
                        || item.status() == VisaStatus.E_VISA || item.status() == VisaStatus.VISA_ON_ARRIVAL)
                        && choices.stream().noneMatch(item -> item.status() == VisaStatus.VISA_FREE);
                if (candidate.status() == VisaStatus.VISA_FREE && citizenSubject) {
                    // Specific passport durations take precedence over a general tourist limit.
                    if (choices.isEmpty() || choices.stream().allMatch(item ->
                            item.status() == VisaStatus.VISA_FREE && item.stayLimit() == null)) {
                        candidates.put(type, new ArrayList<>(List.of(candidate)));
                    }
                } else if (requiresVisa && (candidate.status() == VisaStatus.E_VISA
                        || candidate.status() == VisaStatus.VISA_ON_ARRIVAL)) {
                    candidates.computeIfAbsent(type, key -> new ArrayList<>()).add(candidate);
                }
            }
        }
        List<EntryRule> result = new ArrayList<>();
        String explanation = normalize(text);
        boolean routeCondition = passportTypes(explanation).isEmpty() && explanation.contains("vatandaslar")
                && explanation.contains("uzerinden") && explanation.contains("vize")
                && explanation.contains("gerekmektedir");
        for (PassportType type : PassportType.values()) {
            List<Candidate> choices = candidates.getOrDefault(type, List.of()).stream()
                    .filter(choice -> !discontinued.contains(choice.status())).toList();
            // Age, official-duty and foreign-visa exceptions are known conditions,
            // not an unconditional exemption. Preserve a separately stated general rule.
            boolean hasGeneralRule = choices.stream().anyMatch(choice -> choice.status() != VisaStatus.CONDITIONAL);
            if (hasGeneralRule) choices = choices.stream().filter(choice -> choice.status() != VisaStatus.CONDITIONAL).toList();
            Set<VisaStatus> statuses = new HashSet<>();
            Set<String> durations = new HashSet<>();
            for (Candidate choice : choices) {
                statuses.add(choice.status());
                if (choice.stayLimit() != null) {
                    durations.add(choice.stayLimit());
                }
            }
            // Obtaining an eVisa/arrival visa refines a visa requirement, not a visa exemption.
            if (!statuses.contains(VisaStatus.VISA_FREE)
                    && (statuses.contains(VisaStatus.E_VISA) || statuses.contains(VisaStatus.VISA_ON_ARRIVAL))) {
                statuses.remove(VisaStatus.VISA_REQUIRED);
            }
            if (statuses.equals(Set.of(VisaStatus.E_VISA, VisaStatus.VISA_ON_ARRIVAL))) {
                // Both are visa routes. Do not arbitrarily advertise just one of them.
                statuses = new HashSet<>(Set.of(VisaStatus.VISA_REQUIRED));
            }
            boolean understood = !ambiguous.contains(type) && statuses.size() == 1 && durations.size() <= 1;
            VisaStatus status = understood ? statuses.iterator().next() : VisaStatus.UNKNOWN;
            if (status == VisaStatus.UNKNOWN && choices.isEmpty() && routeCondition) status = VisaStatus.CONDITIONAL;
            if (qualifiedExemptions.contains(type) && status == VisaStatus.VISA_FREE) status = VisaStatus.CONDITIONAL;
            String stayLimit = understood && durations.size() == 1 ? durations.iterator().next() : null;
            if (status == VisaStatus.CONDITIONAL && stayLimit != null) {
                stayLimit = "Koşulu sağlayanlar için: " + stayLimit;
            }
            String conditions = status == VisaStatus.UNKNOWN
                    ? "Otomatik ayrıştırma kesin sonuç üretemedi. Kaynak açıklaması: " + text : text;
            if (status == VisaStatus.UNKNOWN && choices.isEmpty() && !ambiguous.contains(type)) {
                conditions = "Kaynak açıklamasında bu pasaport türüne ait bir kural bulunamadı. Kaynak açıklaması: " + text;
            }
            if (status == VisaStatus.VISA_FREE && EXTRA_REQUIREMENT.matcher(explanation).find()) {
                conditions = "Vize muafiyeti ek giriş işlemlerini kaldırmaz. Kaynakta seyahat izni, form veya sigorta bilgisi bulunuyor; "
                        + "pasaportunuza uygulanan koşulları aşağıdan kontrol edin.\n\n" + conditions;
            }
            if (status == VisaStatus.VISA_FREE && stayLimit == null) {
                conditions = "Kalış süresi kaynakta belirtilmemiştir; bu durum süresiz kalış hakkı vermez.\n\n" + conditions;
            }
            result.add(new EntryRule(country, type, status, stayLimit, conditions, null, null));
        }
        return result;
    }

    private Candidate classify(String sentence, String normalized) {
        return classify(sentence, normalized, false);
    }

    private Candidate classify(String sentence, String normalized, boolean general) {
        // An optional extension is not the initial entitlement. Keep it in conditions.
        String initial = sentence.replaceAll("(?iu)\\([^)]*(?:uzatıl|uzatil)[^)]*\\)", "");
        normalized = normalize(initial);
        // These phrases express a bounded stay, not eligibility such as age or an official delegation.
        String eligibility = normalized.replaceAll("\\b(?:asmamak|gecmemek)\\s+(?:sartiyla|kosuluyla|kaydiyla)\\b", "")
                .replaceAll("\\bkadar olan\\b", "kadar")
                .replaceAll("\\b(?:gun|ay|yil)\\p{L}*\\s+gecerli\\s+(?=(?:elektronik vize|e-vize))", "")
                .replaceAll("\\bgecerli\\s+pasaport\\b", "pasaport");
        String subjectText = normalized.replaceFirst("^pasaport gecerlilik suresi en az \\d+ ay olan\\s+", "")
                .replaceFirst("^\\d+ \\p{L}+ \\d{4} tarihinden itibaren gecerli olmak uzere\\s+", "");
        int firstPassport = subjectText.indexOf("pasaport");
        String subject = firstPassport < 0 ? "" : subjectText.substring(0, firstPassport).strip()
                .replaceFirst("^.*?seyahat (?:eden|edecek)\\s+", "");
        eligibility = eligibility.replace("seyahat edecek olan ", "seyahat edecek ");
        subject = subject.replaceFirst("^olan\\s+", "");
        boolean complex = COMPLEX.matcher(eligibility).find();
        boolean explicitCondition = Pattern.compile("\\byas|\\bheyet|\\bgorev|\\bresmi ziyaret|\\bresmi seyahat|"
                + "\\bikamet izni|\\bvize\\p{L}*\\s+(?:hamili|sahibi|sahip)|\\bbasvuruyla birlikte|\\bon onay|\\bonceden onay|"
                + "\\bolumlu yanit|\\bdavetiye|\\bsadece|\\byalnizca")
                .matcher(eligibility).find();
        // Passport validity is a document requirement, not a restriction on visa exemption.
        if (normalized.startsWith("pasaport gecerlilik suresi en az ")) {
            eligibility = eligibility.replaceFirst("^pasaport gecerlilik suresi en az \\d+ ay olan\\s+", "");
            complex = COMPLEX.matcher(eligibility).find();
        }
        if (NEGATED.matcher(normalized).find()) return null;
        if (complex && explicitCondition) {
            boolean exemption = FREE.matcher(normalized).find();
            String conditionalStay = exemption || PERMITTED_STAY.matcher(normalized).find()
                    ? stayLimit(initial, !exemption) : null;
            return new Candidate(VisaStatus.CONDITIONAL, conditionalStay);
        }
        if ((!general && (firstPassport < 0 || !SUBJECT.matcher(subject).matches())) || complex) {
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
        VisaStatus status = freeCount == 1 ? VisaStatus.VISA_FREE
                : electronic && arrival ? VisaStatus.VISA_REQUIRED
                : electronic ? VisaStatus.E_VISA : arrival ? VisaStatus.VISA_ON_ARRIVAL : VisaStatus.VISA_REQUIRED;
        if (freeCount == 1 && (electronic || arrival)) {
            return null;
        }
        if (freeCount + requiredCount == 0 && !electronic && !arrival) {
            return null;
        }
        // A visa's validity is not necessarily its permitted stay.
        boolean needsExplicitStay = status == VisaStatus.VISA_REQUIRED || VISA_VALIDITY.matcher(normalized).find();
        String stay = stayLimit(initial, needsExplicitStay);
        return new Candidate(status, stay);
    }

    private boolean transitOnly(String text) {
        return text.contains("transit") && (!NORMAL_ENTRY.matcher(text).find()
                || (text.contains("ucuncu ulke") && !Pattern.compile("\\bgiris|\\bcikis").matcher(text).find()));
    }

    private String stayLimit(String sentence) {
        return stayLimit(sentence, false);
    }

    private String stayLimit(String sentence, boolean needsExplicitStay) {
        sentence = sentence.replaceFirst("(?iu),?\\s*(?:bu süre|başvuru üzerine|basvuru uzerine).*uzat[ıi]l.*$", "");
        List<MatchResult> durations = DURATION.matcher(sentence).results().toList();
        boolean[] explicit = new boolean[durations.size()];
        boolean[] unrelated = new boolean[durations.size()];
        boolean[] documentContext = new boolean[durations.size()];
        for (int i = 0; i < durations.size(); i++) {
            MatchResult duration = durations.get(i);
            int previousEnd = i == 0 ? 0 : durations.get(i - 1).end();
            int nextStart = i + 1 == durations.size() ? sentence.length() : durations.get(i + 1).start();
            String before = normalize(sentence.substring(previousEnd, duration.start()));
            before = before.substring(Math.max(before.lastIndexOf(';'), before.lastIndexOf(',')) + 1).strip();
            String after = normalize(sentence.substring(duration.end(), nextStart));
            explicit[i] = STAY_BEFORE_DURATION.matcher(before).find() || STAY_AFTER_DURATION.matcher(after).find();
            unrelated[i] = after.startsWith("gecerli") || DOCUMENT_AFTER_DURATION.matcher(after).find()
                    || (!explicit[i] && (PROCESSING_BEFORE_DURATION.matcher(before).find()
                        || PROCESSING_AFTER_DURATION.matcher(after).find()));
            String prefix = normalize(sentence.substring(0, duration.start()));
            Matcher documentLabel = DOCUMENT_VALIDITY_LABEL.matcher(prefix);
            int labelEnd = -1;
            while (documentLabel.find()) labelEnd = documentLabel.end();
            // "Pasaport geçerlilik süresi 6 ay olan ..." closes the document condition
            // before a later, independent stay entitlement for that passport holder.
            documentContext[i] = labelEnd >= 0 && !Pattern.compile("\\b(?:olan|hamili|sahip)\\b")
                    .matcher(prefix.substring(labelEnd)).find();
        }
        // Keep a bounded reference period with the stay it qualifies, even if only
        // one end has an explicit label: "kalış süresi her 180 günde 90 gün".
        for (int pass = 0; pass < durations.size(); pass++) {
            for (int i = 0; i + 1 < durations.size(); i++) {
                String between = normalize(sentence.substring(durations.get(i).end(), durations.get(i + 1).start()));
                String first = normalize(durations.get(i).group());
                boolean referencePeriod = between.matches("(?:icinde|icerisinde|zarfinda)")
                        || (between.isEmpty() && first.matches(".*\\b(?:gunde|haftada|ayda|yilda)$"))
                        || between.matches("(?:asmamak|gecmemek)\\s+(?:kaydiyla|sartiyla|kosuluyla)\\s+her ziyarette");
                if (referencePeriod && !unrelated[i] && !unrelated[i + 1] && (explicit[i] || explicit[i + 1])) {
                    explicit[i] = true;
                    explicit[i + 1] = true;
                }
            }
        }
        int start = -1;
        int end = -1;
        int count = 0;
        boolean skippedAfterStay = false;
        for (int i = 0; i < durations.size(); i++) {
            MatchResult duration = durations.get(i);
            if (unrelated[i] || ((needsExplicitStay || documentContext[i]) && !explicit[i])) {
                if (start >= 0) skippedAfterStay = true;
                continue;
            }
            // Do not reintroduce discarded document/processing periods by returning
            // a substring that spans them between two unrelated stay expressions.
            if (skippedAfterStay) return null;
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
        // Match passport subjects, not a later reference to a diplomatic mission,
        // a service fee, or another unrelated word in the explanation.
        Matcher groups = Pattern.compile("(?:(?:umuma mahsus|hususi|hizmet|diplomatik|resmi|mahsus|ve|veya)\\b[\\s,()]*)+pasaport")
                .matcher(text);
        while (groups.find()) {
            String group = groups.group();
            if (group.contains("umuma mahsus")) types.add(PassportType.ORDINARY);
            if (group.contains("hususi")) types.add(PassportType.SPECIAL);
            if (group.contains("hizmet")) types.add(PassportType.SERVICE);
            if (group.contains("diplomatik")) types.add(PassportType.DIPLOMATIC);
            if (group.contains("resmi")) {
                types.addAll(EnumSet.of(PassportType.SPECIAL, PassportType.SERVICE, PassportType.DIPLOMATIC));
            }
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
