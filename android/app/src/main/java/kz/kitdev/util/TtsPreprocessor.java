package kz.kitdev.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Подготавливает текст перед отправкой в OpenAI TTS.
 * — Убирает Markdown-разметку
 * — Конвертирует числа в слова на нужном языке
 * — Раскрывает частые сокращения
 * Без этого TTS читает «2024» как «два ноль два четыре» и т.п.
 */
public class TtsPreprocessor {

    public static String prepare(String text, String lang) {
        if (text == null || text.isEmpty()) return text;
        String t = stripMarkdown(text);
        if ("ru".equals(lang))      t = processRu(t);
        else if ("kk".equals(lang)) t = processKk(t);
        return t.replaceAll("\\s{2,}", " ").trim();
    }

    // ─── Markdown ────────────────────────────────────────────────────

    private static String stripMarkdown(String t) {
        t = t.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
        t = t.replaceAll("\\*(.+?)\\*",        "$1");
        t = t.replaceAll("`(.+?)`",             "$1");
        t = t.replaceAll("#{1,6}\\s*",          "");
        t = t.replaceAll("(?m)^[-•*]\\s+",      "");
        return t;
    }

    // ─── Русский ─────────────────────────────────────────────────────

    private static String processRu(String t) {
        t = replacePhones(t);
        t = t.replaceAll("№\\s*(\\d)",           "номер $1");
        t = replacePercent(t, "процентов");
        t = t.replaceAll("(\\d+)\\s*₸",          "$1 тенге");
        t = t.replaceAll("(\\d+)\\s*руб\\.?\\b", "$1 рублей");
        t = t.replaceAll("(\\d+)\\s*км\\b",      "$1 километров");
        t = t.replaceAll("(\\d+)\\s*м\\b",       "$1 метров");
        t = t.replaceAll("\\bул\\.\\s*",          "улица ");
        t = t.replaceAll("\\bпр-т\\.?\\s*",       "проспект ");
        t = t.replaceAll("\\bд\\.\\s*(\\d)",      "дом $1");
        t = t.replaceAll("\\bкв\\.\\s*(\\d)",     "квартира $1");
        t = t.replaceAll("\\bт\\.\\s*е\\.\\s*",   "то есть ");
        t = t.replaceAll("\\bт\\.\\s*к\\.\\s*",   "так как ");
        t = t.replaceAll("\\bи\\s+т\\.\\s*д\\.?", "и так далее");
        t = t.replaceAll("\\bи\\s+т\\.\\s*п\\.?", "и тому подобное");
        t = t.replaceAll("\\bобл\\.\\b",           "область");
        t = t.replaceAll("\\bг\\.\\s+([А-ЯA-Z])", "город $1");
        t = replaceDecimalsRu(t);
        t = replaceIntsRu(t);
        return t;
    }

    // ─── Казахский ───────────────────────────────────────────────────

    private static String processKk(String t) {
        t = replacePhonesKk(t);
        t = t.replaceAll("№\\s*(\\d)",           "нөмір $1");
        t = replacePercent(t, "пайыз");
        t = t.replaceAll("(\\d+)\\s*₸",          "$1 теңге");
        t = t.replaceAll("(\\d+)\\s*км\\b",      "$1 шақырым");
        t = t.replaceAll("(\\d+)\\s*м\\b",       "$1 метр");
        t = replaceDecimalsKk(t);
        t = replaceIntsKk(t);
        return t;
    }

    private static String replacePhonesKk(String text) {
        Matcher m = PHONE_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String digits = m.group().replaceAll("[^\\d]", "");
            if (digits.startsWith("8")) digits = "7" + digits.substring(1);
            m.appendReplacement(sb, Matcher.quoteReplacement(spellDigitsKk(digits)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String spellDigitsKk(String digits) {
        StringBuilder sb = new StringBuilder();
        for (char c : digits.toCharArray()) {
            if (c >= '0' && c <= '9') {
                if (sb.length() > 0) sb.append(' ');
                sb.append(KK_DIGITS[c - '0']);
            }
        }
        return sb.toString();
    }

    private static String replaceDecimalsKk(String text) {
        Matcher m = Pattern.compile("\\b(\\d+)[.,](\\d+)\\b").matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            long intPart  = Long.parseLong(m.group(1));
            long fracPart = Long.parseLong(m.group(2));
            String replacement = numToKk(intPart) + " бүтін " + numToKk(fracPart);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceIntsKk(String text) {
        Matcher m = Pattern.compile("\\b(\\d+)\\b").matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(numToKk(n)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ─── Казахские числа → слова ─────────────────────────────────────

    private static final String[] KK_DIGITS = {
        "нөл", "бір", "екі", "үш", "төрт", "бес", "алты", "жеті", "сегіз", "тоғыз"
    };

    private static final String[] KK_ONES = {
        "", "бір", "екі", "үш", "төрт", "бес", "алты", "жеті", "сегіз", "тоғыз",
        "он", "он бір", "он екі", "он үш", "он төрт", "он бес",
        "он алты", "он жеті", "он сегіз", "он тоғыз"
    };

    private static final String[] KK_TENS = {
        "", "", "жиырма", "отыз", "қырық", "елу",
        "алпыс", "жетпіс", "сексен", "тоқсан"
    };

    private static final String[] KK_HUNDREDS = {
        "", "жүз", "екі жүз", "үш жүз", "төрт жүз", "бес жүз",
        "алты жүз", "жеті жүз", "сегіз жүз", "тоғыз жүз"
    };

    public static String numToKk(long n) {
        if (n == 0) return "нөл";
        if (n < 0)  return "минус " + numToKk(-n);
        StringBuilder sb = new StringBuilder();
        if (n >= 1_000_000_000L) {
            long b = n / 1_000_000_000L;
            sb.append(hundredsKk(b)).append(" миллиард ");
            n %= 1_000_000_000L;
        }
        if (n >= 1_000_000L) {
            long m = n / 1_000_000L;
            sb.append(hundredsKk(m)).append(" миллион ");
            n %= 1_000_000L;
        }
        if (n >= 1_000L) {
            long th = n / 1_000L;
            sb.append(hundredsKk(th)).append(" мың ");
            n %= 1_000L;
        }
        if (n > 0) sb.append(hundredsKk(n));
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static String hundredsKk(long n) {
        if (n <= 0) return "";
        StringBuilder sb = new StringBuilder();
        if (n >= 100) {
            sb.append(KK_HUNDREDS[(int)(n / 100)]);
            n %= 100;
            if (n > 0) sb.append(' ');
        }
        if (n >= 20) {
            sb.append(KK_TENS[(int)(n / 10)]);
            n %= 10;
            if (n > 0) sb.append(' ');
        }
        if (n > 0) sb.append(KK_ONES[(int)n]);
        return sb.toString().trim();
    }

    // ─── Телефоны ────────────────────────────────────────────────────

    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?:\\+7|8)[\\s\\-]?\\(?\\d{3}\\)?[\\s\\-]?\\d{3}[\\s\\-]?\\d{2}[\\s\\-]?\\d{2}");

    private static String replacePhones(String text) {
        Matcher m = PHONE_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String digits = m.group().replaceAll("[^\\d]", "");
            if (digits.startsWith("8")) digits = "7" + digits.substring(1);
            m.appendReplacement(sb, Matcher.quoteReplacement(spellDigits(digits)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String spellDigits(String digits) {
        String[] ru = {"ноль","один","два","три","четыре","пять","шесть","семь","восемь","девять"};
        StringBuilder sb = new StringBuilder();
        for (char c : digits.toCharArray()) {
            if (c >= '0' && c <= '9') {
                if (sb.length() > 0) sb.append(' ');
                sb.append(ru[c - '0']);
            }
        }
        return sb.toString();
    }

    // ─── Проценты ────────────────────────────────────────────────────

    private static String replacePercent(String text, String word) {
        Matcher m = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*%").matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + " " + word));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ─── Дробные числа ───────────────────────────────────────────────

    private static String replaceDecimalsRu(String text) {
        Matcher m = Pattern.compile("\\b(\\d+)[.,](\\d+)\\b").matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            long intPart  = Long.parseLong(m.group(1));
            long fracPart = Long.parseLong(m.group(2));
            String replacement = numToRu(intPart) + " целых " + numToRu(fracPart);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ─── Целые числа ─────────────────────────────────────────────────

    private static String replaceIntsRu(String text) {
        Matcher m = Pattern.compile("\\b(\\d+)\\b").matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(numToRu(n)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ─── Числа → русские слова ───────────────────────────────────────

    private static final String[] ONES = {
        "", "один", "два", "три", "четыре", "пять", "шесть",
        "семь", "восемь", "девять", "десять", "одиннадцать",
        "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
        "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"
    };
    private static final String[] TENS = {
        "", "", "двадцать", "тридцать", "сорок", "пятьдесят",
        "шестьдесят", "семьдесят", "восемьдесят", "девяносто"
    };
    private static final String[] HUNDREDS = {
        "", "сто", "двести", "триста", "четыреста", "пятьсот",
        "шестьсот", "семьсот", "восемьсот", "девятьсот"
    };

    public static String numToRu(long n) {
        if (n == 0) return "ноль";
        if (n < 0)  return "минус " + numToRu(-n);
        StringBuilder sb = new StringBuilder();
        if (n >= 1_000_000_000L) {
            long b = n / 1_000_000_000L;
            sb.append(hundreds(b, false)).append(' ').append(billionWord(b)).append(' ');
            n %= 1_000_000_000L;
        }
        if (n >= 1_000_000L) {
            long m = n / 1_000_000L;
            sb.append(hundreds(m, false)).append(' ').append(millionWord(m)).append(' ');
            n %= 1_000_000L;
        }
        if (n >= 1_000L) {
            long th = n / 1_000L;
            sb.append(thousandsStr(th)).append(' ');
            n %= 1_000L;
        }
        if (n > 0) sb.append(hundreds(n, false));
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static String thousandsStr(long n) {
        // тысяча — женский род: одна тысяча, две тысячи
        String base = hundreds(n, true);
        return base + " " + thousandWord(n);
    }

    private static String hundreds(long n, boolean feminine) {
        if (n <= 0) return "";
        StringBuilder sb = new StringBuilder();
        if (n >= 100) { sb.append(HUNDREDS[(int)(n / 100)]); n %= 100; if (n > 0) sb.append(' '); }
        if (n >= 20)  { sb.append(TENS[(int)(n / 10)]);      n %= 10;  if (n > 0) sb.append(' '); }
        if (n > 0) {
            if (n == 1 && feminine) sb.append("одна");
            else if (n == 2 && feminine) sb.append("две");
            else sb.append(ONES[(int) n]);
        }
        return sb.toString().trim();
    }

    private static String thousandWord(long n) {
        long mod100 = n % 100, mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 19) return "тысяч";
        if (mod10 == 1) return "тысяча";
        if (mod10 >= 2 && mod10 <= 4) return "тысячи";
        return "тысяч";
    }

    private static String millionWord(long n) {
        long mod100 = n % 100, mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 19) return "миллионов";
        if (mod10 == 1) return "миллион";
        if (mod10 >= 2 && mod10 <= 4) return "миллиона";
        return "миллионов";
    }

    private static String billionWord(long n) {
        long mod100 = n % 100, mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 19) return "миллиардов";
        if (mod10 == 1) return "миллиард";
        if (mod10 >= 2 && mod10 <= 4) return "миллиарда";
        return "миллиардов";
    }
}
