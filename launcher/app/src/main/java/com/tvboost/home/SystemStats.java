package com.tvboost.home;

import java.util.Locale;

/** The numbers on the system tile. Pure logic, no Android types, so it runs in JVM tests. */
final class SystemStats {
    private static final long MB = 1024L * 1024L;
    private static final long GB = MB * 1024L;

    private SystemStats() {
    }

    /** How much is in use, 0 to 100. A value the system could not report reads as 0, never as full. */
    static int usedPercent(long total, long free) {
        if (total <= 0 || free < 0 || free > total) return 0;
        return (int) Math.round(100d * (total - free) / total);
    }

    /** {total, free} in bytes from the last line of `df -k`, or null if it is not one. */
    static long[] parseDf(String output) {
        String[] lines = output.trim().split("\\n");
        String[] parts = lines[lines.length - 1].trim().split("\\s+");
        if (parts.length < 4) return null;
        try {
            return new long[]{Long.parseLong(parts[1]) * 1024L, Long.parseLong(parts[3]) * 1024L};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "612 MB" under a gigabyte, "1.1 GB" from there up. */
    static String size(long bytes) {
        if (bytes < GB) return Math.round((double) bytes / MB) + " MB";
        return String.format(Locale.US, "%.1f GB", (double) bytes / GB);
    }
}
