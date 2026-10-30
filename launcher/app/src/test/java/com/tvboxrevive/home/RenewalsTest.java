package com.tvboxrevive.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RenewalsTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);

    @Test
    public void aFutureDateStaysPut() {
        assertEquals(LocalDate.of(2026, 11, 12), Renewals.next(LocalDate.of(2026, 11, 12), "monthly", TODAY));
    }

    @Test
    public void aPastMonthlyDateRollsToTheNextMonth() {
        assertEquals(LocalDate.of(2026, 11, 12), Renewals.next(LocalDate.of(2026, 1, 12), "monthly", TODAY));
    }

    @Test
    public void aPastYearlyDateRollsToNextYear() {
        assertEquals(LocalDate.of(2027, 3, 1), Renewals.next(LocalDate.of(2025, 3, 1), "yearly", TODAY));
    }

    @Test
    public void today_countsAsDue() {
        assertEquals(TODAY, Renewals.next(TODAY, "monthly", TODAY));
    }

    @Test
    public void the31stComesBackAfterAShortMonth() {
        assertEquals(LocalDate.of(2026, 11, 30), Renewals.next(LocalDate.of(2026, 8, 31), "monthly", LocalDate.of(2026, 11, 1)));
        assertEquals(LocalDate.of(2026, 12, 31), Renewals.next(LocalDate.of(2026, 8, 31), "monthly", LocalDate.of(2026, 12, 1)));
    }

    @Test
    public void aOneOffDateThatPassedIsDropped() {
        List<Renewals.Entry> entries = Collections.singletonList(
                new Renewals.Entry("Old", LocalDate.of(2026, 1, 1), "once"));
        assertTrue(Renewals.upcoming(entries, TODAY, 3).isEmpty());
    }

    @Test
    public void soonestComesFirstAndTheListIsCapped() {
        List<Renewals.Entry> entries = Arrays.asList(
                new Renewals.Entry("Later", LocalDate.of(2026, 12, 20), "monthly"),
                new Renewals.Entry("Soon", LocalDate.of(2026, 11, 2), "monthly"),
                new Renewals.Entry("Middle", LocalDate.of(2026, 11, 10), "yearly"),
                new Renewals.Entry("Last", LocalDate.of(2027, 2, 1), "yearly"));
        List<Renewals.Entry> shown = Renewals.upcoming(entries, TODAY, 3);
        assertEquals(3, shown.size());
        assertEquals("Soon", shown.get(0).name);
        assertEquals("Middle", shown.get(1).name);
        assertEquals("Later", shown.get(2).name);
    }

    @Test
    public void daysUntilCountsCalendarDays() {
        assertEquals(3, Renewals.daysUntil(LocalDate.of(2026, 11, 2), TODAY));
    }

    @Test
    public void forYouPutsTheMostOpenedFirstAndKeepsDockOrderForTies() {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("c", 5);
        counts.put("b", 2);
        List<String> ranked = ForYou.rank(counts, new ArrayList<>(Arrays.asList("a", "b", "c", "d")), 3);
        assertEquals(Arrays.asList("c", "b", "a"), ranked);
    }
}
