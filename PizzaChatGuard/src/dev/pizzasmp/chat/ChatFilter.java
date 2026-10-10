package dev.pizzasmp.chat;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Text normalization for the chat filter. Folds the usual evasions back to plain lowercase letters before matching:
 * compatibility forms (full-width, ligatures), accents and other combining marks (including zalgo stacks), invisible
 * format characters (zero-width spaces and joiners), Cyrillic and Greek lookalikes, and leetspeak digits and symbols.
 */
final class ChatFilter {
    private static final Map<Integer, Character> FOLD = new HashMap<>();

    static {
        // Cyrillic and Greek letters that render like Latin ones.
        String[][] pairs = {
            {"а", "a"}, {"в", "b"}, {"с", "c"}, {"ԁ", "d"}, {"е", "e"}, {"ё", "e"}, {"һ", "h"}, {"н", "h"}, {"і", "i"},
            {"ї", "i"}, {"ј", "j"}, {"к", "k"}, {"м", "m"}, {"о", "o"}, {"р", "p"}, {"ԛ", "q"}, {"ѕ", "s"}, {"т", "t"},
            {"у", "y"}, {"х", "x"}, {"ԝ", "w"}, {"г", "r"}, {"п", "n"}, {"α", "a"}, {"β", "b"}, {"ε", "e"}, {"η", "n"},
            {"ι", "i"}, {"κ", "k"}, {"ν", "v"}, {"ο", "o"}, {"ρ", "p"}, {"τ", "t"}, {"υ", "u"}, {"χ", "x"}, {"γ", "y"},
            {"ɡ", "g"}, {"ı", "i"}, {"ł", "l"}, {"ø", "o"}, {"ß", "s"}, {"æ", "a"}, {"œ", "o"},
            // Leetspeak.
            {"4", "a"}, {"@", "a"}, {"8", "b"}, {"(", "c"}, {"3", "e"}, {"6", "g"}, {"9", "g"}, {"1", "i"}, {"!", "i"},
            {"|", "i"}, {"0", "o"}, {"5", "s"}, {"$", "s"}, {"7", "t"}, {"+", "t"}, {"2", "z"}};
        for (String[] p : pairs) FOLD.put(p[0].codePointAt(0), p[1].charAt(0));
    }

    private ChatFilter() { }

    /** Lowercase letters with lookalikes, leetspeak, accents and invisible characters folded; other characters as spaces. */
    static String fold(String input) {
        String decomposed = Normalizer.normalize(input, Normalizer.Form.NFKD);
        StringBuilder out = new StringBuilder(decomposed.length());
        decomposed.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK || type == Character.FORMAT) return;
            int lower = Character.toLowerCase(cp);
            Character mapped = FOLD.get(lower);
            if (mapped != null) out.append(mapped);
            else if (lower >= 'a' && lower <= 'z') out.append((char) lower);
            else out.append(' ');
        });
        return out.toString();
    }

    /** Runs of the same letter reduced to one ("hellooo" -> "helo"), so stretched spellings compare equal. */
    static String collapse(String s) {
        StringBuilder out = new StringBuilder(s.length());
        char last = 0;
        for (char c : s.toCharArray()) {
            if (c != last) out.append(c);
            last = c;
        }
        return out.toString();
    }

    /**
     * The first word from {@code words} found in {@code message}, or null. Each folded word matches as a whole token,
     * with repeated letters collapsed on both sides; words of at least {@code substringMin} letters also match inside
     * longer tokens, except tokens listed as safe words. Runs of short fragments are glued ("w o rd", "w.or-d").
     */
    static String findWord(String message, Set<String> words, Set<String> safe, int substringMin) {
        String[] tokens = fold(message).trim().split(" +");
        StringBuilder run = new StringBuilder();
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            // Short fragments ("ab cd ef") are glued into one run to catch spaced-out spellings; whole words are not.
            if (token.length() <= 3) run.append(token);
            else {
                String hit = matchRun(run, words, substringMin);
                if (hit != null) return hit;
                run.setLength(0);
            }
            if (safe.contains(token)) continue;
            String collapsed = collapse(token);
            for (String word : words) {
                String w = normalizedWord(word);
                if (w.isEmpty()) continue;
                if (collapsed.equals(w)) return word;
                if (w.length() >= substringMin && collapsed.contains(w)) return word;
            }
        }
        return matchRun(run, words, substringMin);
    }

    private static String matchRun(StringBuilder run, Set<String> words, int substringMin) {
        if (run.length() < 3) return null;
        String glued = collapse(run.toString());
        for (String word : words) {
            String w = normalizedWord(word);
            if (w.isEmpty()) continue;
            if (glued.equals(w) || (w.length() >= substringMin && glued.contains(w))) return word;
        }
        return null;
    }

    private static String normalizedWord(String word) {
        return collapse(fold(word).replace(" ", ""));
    }

    /** The first phrase found in the message as whole words (after folding), or null. */
    static String findPhrase(String message, java.util.List<String> phrases) {
        String folded = " " + fold(message).trim().replaceAll(" +", " ") + " ";
        for (String phrase : phrases) {
            String p = fold(phrase).trim().replaceAll(" +", " ");
            if (!p.isEmpty() && folded.contains(" " + p + " ")) return phrase;
        }
        return null;
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
