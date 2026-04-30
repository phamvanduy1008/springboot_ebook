package com.example.epub.core.model.catalog;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
public record MetricWindow(String period,Instant from,Instant to) {
 public static MetricWindow of(String period,Clock clock) {
  var today=LocalDate.now(clock.withZone(ZoneOffset.UTC));
  LocalDate start=switch(period) {case "week" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));case "month" -> today.withDayOfMonth(1);case "year" -> today.withDayOfYear(1);default -> throw new IllegalArgumentException("Unsupported period");};
  LocalDate end=switch(period) {case "week" -> start.plusWeeks(1);case "month" -> start.plusMonths(1);default -> start.plusYears(1);};
  return new MetricWindow(period,start.atStartOfDay(ZoneOffset.UTC).toInstant(),end.atStartOfDay(ZoneOffset.UTC).toInstant());
 }
}
