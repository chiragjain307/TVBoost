package com.tvboxrevive.home;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SystemStatsTest {
    private static final long MB = 1024L * 1024L;
    private static final long GB = 1024L * MB;

    @Test
    public void usedIsTheShareThatIsNotFree() {
        assertEquals(53, SystemStats.usedPercent(1024 * MB, 482 * MB));
        assertEquals(100, SystemStats.usedPercent(GB, 0));
        assertEquals(0, SystemStats.usedPercent(GB, GB));
    }

    @Test
    public void aBadReadingIsNeverShownAsFull() {
        assertEquals(0, SystemStats.usedPercent(0, 0));
        assertEquals(0, SystemStats.usedPercent(GB, -1));
        assertEquals(0, SystemStats.usedPercent(GB, 2 * GB));
    }

    @Test
    public void dfGivesTheRealFiguresFromItsLastLine() {
        long[] disk = SystemStats.parseDf("Filesystem 1K-blocks Used Available Use% Mounted on\n"
                + "/dev/block/mmcblk0p21 4163652 1749680 2182756 45% /data\n");
        assertEquals(4163652L * 1024L, disk[0]);
        assertEquals(2182756L * 1024L, disk[1]);
    }

    @Test
    public void anUnreadableDfIsNotGuessedAt() {
        assertEquals(null, SystemStats.parseDf("df: /data: Permission denied"));
        assertEquals(null, SystemStats.parseDf(""));
    }

    @Test
    public void sizesSwitchToGigabytesAtOne() {
        assertEquals("612 MB", SystemStats.size(612 * MB));
        assertEquals("1.0 GB", SystemStats.size(GB));
        assertEquals("3.9 GB", SystemStats.size(3994 * MB));
    }
}
