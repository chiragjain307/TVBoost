package com.tvboxrevive.home;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Upcoming subscription renewals for the Home panel. Android gives no app another app's billing dates, so
 * these come from renewals.json, which the owner fills in once. A monthly or yearly date rolls forward by
 * itself, so the panel keeps working without being edited. Pure logic, no Android types, tested in the JVM.
 */
final class Renewals {

    static final class Entry {
        final String name;
        final LocalDate date;
        final String cycle;

        Entry(String name, LocalDate date, String cycle) {
            this.name = name;
            this.date = date;
            this.cycle = cycle;
        }
    }

    private Renewals() {
    }

    /** The first renewal on or after today: monthly and yearly dates step forward, a one-off stays put. */
    static LocalDate next(LocalDate date, String cycle, LocalDate today) {
        LocalDate next = date;
        boolean monthly = "monthly".equals(cycle);
        if (!monthly && !"yearly".equals(cycle)) return next;
        // Stepped from the original date each time, so a 31st comes back after a short month.
        for (int steps = 1; next.isBefore(today) && steps < 2400; steps++) {
            next = monthly ? date.plusMonths(steps) : date.plusYears(steps);
        }
        return next;
    }

    /** The soonest renewals still to come, each with its next date, at most {@code limit} of them. */
    static List<Entry> upcoming(List<Entry> entries, LocalDate today, int limit) {
        List<Entry> due = new ArrayList<>();
        for (Entry e : entries) {
            LocalDate next = next(e.date, e.cycle, today);
            if (!next.isBefore(today)) due.add(new Entry(e.name, next, e.cycle));
        }
        due.sort(Comparator.comparing((Entry e) -> e.date).thenComparing(e -> e.name));
        return new ArrayList<>(due.subList(0, Math.min(limit, due.size())));
    }

    static long daysUntil(LocalDate date, LocalDate today) {
        return ChronoUnit.DAYS.between(today, date);
    }
}
