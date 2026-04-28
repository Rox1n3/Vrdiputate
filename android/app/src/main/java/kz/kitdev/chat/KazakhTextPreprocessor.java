package kz.kitdev.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prepares text for Kazakh TTS so that numbers, dates, and Russian month names
 * are spoken in pure Kazakh rather than being read with a Russian accent.
 *
 * Pipeline:
 *  1. Replace Russian month names → Kazakh equivalents
 *  2. Replace date patterns (DD.MM.YYYY, DD/MM/YYYY) → Kazakh date phrase
 *  3. Replace remaining digit sequences → Kazakh number words
 */
public final class KazakhTextPreprocessor {

    private KazakhTextPreprocessor() {}

    // ---------------------------------------------------------------
    //  Number tables
    // ---------------------------------------------------------------

    /** Units: index 0 unused, 1=бір … 9=тоғыз */
    private static final String[] ONES = {
        "", "бір", "екі", "үш", "төрт", "бес", "алты", "жеті", "сегіз", "тоғыз"
    };

    /** Tens: index 0–1 unused, 2=жиырма … 9=тоқсан */
    private static final String[] TENS = {
        "", "он", "жиырма", "отыз", "қырық", "елу", "алпыс", "жетпіс", "сексен", "тоқсан"
    };

    /** Kazakh month names in order Jan–Dec */
    private static final String[] MONTHS_KK = {
        "қаңтар", "ақпан", "наурыз", "сәуір", "мамыр", "маусым",
        "шілде", "тамыз", "қыркүйек", "қазан", "қараша", "желтоқсан"
    };

    /** Russian month stems → Kazakh month */
    private static final String[][] MONTH_MAP = {
        { "январ[яьеи]*",   "қаңтар"    },
        { "феврал[яьеи]*",  "ақпан"     },
        { "март[еа]?",       "наурыз"    },
        { "апрел[яьеи]*",   "сәуір"     },
        { "мая|май",         "мамыр"     },
        { "июн[яьеи]*",     "маусым"    },
        { "июл[яьеи]*",     "шілде"     },
        { "август[еа]?",     "тамыз"     },
        { "сентябр[яьеи]*", "қыркүйек"  },
        { "октябр[яьеи]*",  "қазан"     },
        { "ноябр[яьеи]*",   "қараша"    },
        { "декабр[яьеи]*",  "желтоқсан" }
    };

    // ---------------------------------------------------------------
    //  Public entry point
    // ---------------------------------------------------------------

    /**
     * Processes text intended for Kazakh TTS so all numbers and dates
     * are spelled out in native Kazakh words.
     */
    public static String process(String text) {
        if (text == null || text.isEmpty()) return text;
        text = replaceMonthNames(text);
        text = replaceDatePatterns(text);
        text = replaceNumbers(text);
        return text;
    }

    // ---------------------------------------------------------------
    //  Step 1: Russian month names → Kazakh
    // ---------------------------------------------------------------

    private static String replaceMonthNames(String text) {
        for (String[] pair : MONTH_MAP) {
            text = text.replaceAll("(?i)" + pair[0], pair[1]);
        }
        return text;
    }

    // ---------------------------------------------------------------
    //  Step 2: Numeric date patterns → Kazakh date phrase
    //  Handles: DD.MM.YYYY  DD/MM/YYYY  DD-MM-YYYY
    // ---------------------------------------------------------------

    private static final Pattern DATE_PATTERN =
            Pattern.compile("(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})");

    private static String replaceDatePatterns(String text) {
        Matcher m = DATE_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int day   = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            int year  = Integer.parseInt(m.group(3));

            String dayStr   = numberToKazakh(day);
            String monthStr = (month >= 1 && month <= 12)
                    ? MONTHS_KK[month - 1]
                    : numberToKazakh(month);
            String yearStr  = numberToKazakh(year) + " жыл";

            m.appendReplacement(sb, dayStr + " " + monthStr + " " + yearStr);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---------------------------------------------------------------
    //  Step 3: Remaining digit sequences → Kazakh words
    //  Skips numbers that are part of longer alphanumeric tokens
    //  (e.g. article codes like "ст.123" stay intact — only pure numbers replaced)
    // ---------------------------------------------------------------

    private static final Pattern NUMBER_PATTERN = Pattern.compile("(?<![\\wА-Яа-яҚқҒғҮүҰұӘәІіҢңӨөҺһ])(\\d+)(?![\\wА-Яа-яҚқҒғҮүҰұӘәІіҢңӨөҺһ])");

    private static String replaceNumbers(String text) {
        Matcher m = NUMBER_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            try {
                long n = Long.parseLong(m.group(1));
                m.appendReplacement(sb, numberToKazakh(n));
            } catch (NumberFormatException e) {
                m.appendReplacement(sb, m.group(0));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---------------------------------------------------------------
    //  Core: integer → Kazakh words
    // ---------------------------------------------------------------

    public static String numberToKazakh(long n) {
        if (n == 0) return "нөл";
        if (n < 0)  return "минус " + numberToKazakh(-n);

        StringBuilder sb = new StringBuilder();

        if (n >= 1_000_000_000_000L) {
            sb.append(numberToKazakh(n / 1_000_000_000_000L)).append(" триллион ");
            n %= 1_000_000_000_000L;
        }
        if (n >= 1_000_000_000L) {
            sb.append(numberToKazakh(n / 1_000_000_000L)).append(" миллиард ");
            n %= 1_000_000_000L;
        }
        if (n >= 1_000_000L) {
            sb.append(numberToKazakh(n / 1_000_000L)).append(" миллион ");
            n %= 1_000_000L;
        }
        if (n >= 1000L) {
            long th = n / 1000L;
            // "бір мың" in Kazakh (unlike Russian "одна тысяча")
            sb.append(numberToKazakh(th)).append(" мың ");
            n %= 1000L;
        }
        if (n >= 100L) {
            sb.append(ONES[(int) (n / 100L)]).append(" жүз ");
            n %= 100L;
        }
        if (n >= 10L) {
            sb.append(TENS[(int) (n / 10L)]).append(" ");
            n %= 10L;
        }
        if (n > 0L) {
            sb.append(ONES[(int) n]).append(" ");
        }

        return sb.toString().trim();
    }
}
