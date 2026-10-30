package com.tvboxrevive.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The For You row: the apps this viewer opens most, counted on the TV and never sent anywhere. Android
 * keeps other apps' recommendations from a launcher that is not part of the firmware, so the row ranks apps
 * rather than shows.
 */
final class ForYou {
    private static final String PREFS = "for_you";
    /** Set once by HomeExtras, so a design without the row does not write to storage on every launch. */
    static boolean enabled;

    private ForYou() {
    }

    static void record(Context ctx, String pkg) {
        if (!enabled) return;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putInt(pkg, prefs.getInt(pkg, 0) + 1).apply();
    }

    /** Most opened first, then the fallback order, so a new TV starts with its dock apps. */
    static List<String> rank(Map<String, Integer> counts, List<String> installed, int limit) {
        List<String> ranked = new ArrayList<>(installed);
        ranked.sort(Comparator.comparingInt((String pkg) -> -counts.getOrDefault(pkg, 0)));
        return new ArrayList<>(ranked.subList(0, Math.min(limit, ranked.size())));
    }

    static List<String> rank(Context ctx, List<String> installed, int limit) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (Map.Entry<String, ?> e : ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll().entrySet()) {
            if (e.getValue() instanceof Integer) counts.put(e.getKey(), (Integer) e.getValue());
        }
        return rank(counts, installed, limit);
    }
}
