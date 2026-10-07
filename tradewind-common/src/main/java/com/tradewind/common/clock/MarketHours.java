package com.tradewind.common.clock;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Regular US equity session: Monday to Friday, 9:30 (inclusive) to 16:00 (exclusive) New York time. No holidays. */
public final class MarketHours {

    public static final ZoneId ZONE = ZoneId.of("America/New_York");
    public static final LocalTime OPEN = LocalTime.of(9, 30);
    public static final LocalTime CLOSE = LocalTime.of(16, 0);

    private MarketHours() {
    }

    public static boolean isTradingDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    public static boolean isOpen(ZonedDateTime time) {
        ZonedDateTime local = time.withZoneSameInstant(ZONE);
        LocalTime t = local.toLocalTime();
        return isTradingDay(local.toLocalDate()) && !t.isBefore(OPEN) && t.isBefore(CLOSE);
    }

    public static ZonedDateTime closeOf(LocalDate date) {
        return date.atTime(CLOSE).atZone(ZONE);
    }
}
