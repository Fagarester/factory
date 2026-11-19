package com.factorynative;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The app's texts in the phone's language. The code keeps its texts in Russian (the source language); the
 * translations are assets/i18n/<language>.tsv, one "source<TAB>translation" per line (\n, \t escaped). A language
 * without a file, and any text missing in a file, falls back to English; Russian shows the source as is.
 */
final class Tr {
    private static Map<String, String> lang, english;
    private static boolean russian;

    private Tr() {}

    static synchronized void init(Context c) {
        if (lang != null) return;
        String code = Locale.getDefault().getLanguage();
        if ("in".equals(code)) code = "id";
        russian = "ru".equals(code);
        english = load(c, "en");
        Map<String, String> own = russian ? new HashMap<>() : load(c, code);
        lang = own.isEmpty() ? english : own;
    }

    private static Map<String, String> load(Context c, String code) {
        Map<String, String> m = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("i18n/" + code + ".tsv"),
                StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                int tab = line.indexOf('\t');
                if (tab <= 0 || line.startsWith("#")) continue;
                m.put(unescape(line.substring(0, tab)), unescape(line.substring(tab + 1)));
            }
        } catch (Exception ignored) {
            // no file for this language
        }
        return m;
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\t", "\t");
    }

    /** The text in the phone's language (source text in Russian). */
    static String t(String source) {
        if (russian || lang == null || source == null) return source;
        String s = lang.get(source);
        if (s == null && lang != english) s = english.get(source);
        return s != null ? s : source;
    }
}
