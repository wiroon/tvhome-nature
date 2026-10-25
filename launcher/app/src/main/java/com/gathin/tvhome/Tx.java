package com.gathin.tvhome;

import java.util.Locale;

/** The few texts of the Dock, wallpaper manager and weather strip: Thai when the TV's language is Thai, English otherwise. */
final class Tx {
    private Tx() {}

    static boolean thai() { return "th".equals(Locale.getDefault().getLanguage()); }

    static String t(String thai, String english) { return thai() ? thai : english; }
}
