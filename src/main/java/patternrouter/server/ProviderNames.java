package patternrouter.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Matching a renamed provider against the NEI tab title ("Fluid Solidifier"). */
final class ProviderNames {

    /** Words too common in machine names to identify one. */
    private static final Set<String> GENERIC_WORDS = new HashSet<>(
        Arrays.asList(
            "большой",
            "большая",
            "малый",
            "мега",
            "промышленный",
            "промышленная",
            "продвинутый",
            "машина",
            // Shared by the EBF, arc, plasma arc, vacuum and electric furnaces: "Доменная печь" must not pull in a
            // provider named "Дуговая печь".
            "печь",
            "электрическая",
            "электрический",
            "large",
            "mega",
            "industrial",
            "advanced",
            "machine",
            "multiblock",
            "furnace",
            "electric"));
    private static final int MIN_WORD_LENGTH = 4;
    /** Words are compared by this many first letters, so word endings don't matter. */
    private static final int COMPARED_PREFIX = 5;

    private ProviderNames() {}

    /** Significant words of the NEI tab title: letters only, 4+ characters, not common words. */
    static List<String> significantWords(String title) {
        List<String> out = new ArrayList<>();
        if (title == null) return out;
        for (String w : title.toLowerCase(Locale.ROOT)
            .split("[^\\p{L}]+")) {
            if (w.length() >= MIN_WORD_LENGTH && !GENERIC_WORDS.contains(w)) out.add(w);
        }
        return out;
    }

    /** The provider name contains one of the words ("отвердитель" ~ "Отвердителя", "реактор" ~ "Хим реактор 2"). */
    static boolean containsAny(String name, List<String> words) {
        if (name == null || name.isEmpty() || words.isEmpty()) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (String w : words)
            if (n.contains(w.length() > COMPARED_PREFIX ? w.substring(0, COMPARED_PREFIX) : w)) return true;
        return false;
    }
}
