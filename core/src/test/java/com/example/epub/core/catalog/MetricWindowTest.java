
package com.example.epub.core.catalog;
import com.example.epub.core.model.catalog.MetricWindow;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MetricWindowTest {
 @Test void calendarWindowsUseUtcMondayAndExclusiveNextBoundary() {
  var clock=Clock.fixed(Instant.parse("2024-02-29T23:59:59Z"),ZoneOffset.ofHours(7));
  var week=MetricWindow.of("week",clock);assertEquals(Instant.parse("2024-02-26T00:00:00Z"),week.from());assertEquals(Instant.parse("2024-03-04T00:00:00Z"),week.to());
  var month=MetricWindow.of("month",clock);assertEquals(Instant.parse("2024-02-01T00:00:00Z"),month.from());assertEquals(Instant.parse("2024-03-01T00:00:00Z"),month.to());
  var year=MetricWindow.of("year",clock);assertEquals(Instant.parse("2025-01-01T00:00:00Z"),year.to());
  assertEquals(Instant.parse("2024-03-04T00:00:00Z"),MetricWindow.of("week",Clock.fixed(week.to(),ZoneOffset.UTC)).from());
  assertThrows(IllegalArgumentException.class,()->MetricWindow.of("all",clock));
 }
}
