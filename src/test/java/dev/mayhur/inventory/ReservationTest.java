package dev.mayhur.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import dev.mayhur.inventory.reservation.Reservation;
import dev.mayhur.inventory.reservation.ReservationService;
import dev.mayhur.inventory.reservation.ReservationService.Line;
import dev.mayhur.inventory.stock.StockService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:reservation-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "inventory.expiry-check-ms=3600000"
})
class ReservationTest {

    static class MovableClock extends Clock {
        Instant now = Instant.parse("2026-10-14T08:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        MovableClock testClock() {
            return new MovableClock();
        }
    }

    @Autowired ReservationService reservations;
    @Autowired StockService stock;
    @Autowired MovableClock clock;

    /** Each test works on its own SKU so they don't interfere through the shared database. */
    private String newSku(String warehouse, int qty) {
        var sku = "T-" + UUID.randomUUID().toString().substring(0, 8);
        stock.receive(sku, warehouse, qty);
        return sku;
    }

    private int available(String sku) {
        return stock.levels(sku).stream().mapToInt(StockService.Level::available).sum();
    }

    @Test
    void reserveConfirmMovesStockOutOfOnHand() {
        var sku = newSku("JHB", 50);
        var r = reservations.reserve(key(), "ORD-1", List.of(new Line(sku, null, 20)), null);
        assertThat(available(sku)).isEqualTo(30);

        reservations.confirm(r.getId());
        var level = stock.levels(sku).get(0);
        assertThat(level.onHand()).isEqualTo(30);
        assertThat(level.reserved()).isZero();
    }

    @Test
    void releaseGivesStockBack() {
        var sku = newSku("JHB", 10);
        var r = reservations.reserve(key(), "ORD-2", List.of(new Line(sku, null, 10)), null);
        reservations.release(r.getId());
        assertThat(available(sku)).isEqualTo(10);
        assertThat(reservations.get(r.getId()).getStatus()).isEqualTo(Reservation.Status.RELEASED);
    }

    @Test
    void allOrNothingWhenOneLineIsShort() {
        var plenty = newSku("JHB", 100);
        var scarce = newSku("JHB", 3);
        assertThatThrownBy(() -> reservations.reserve(key(), "ORD-3",
                List.of(new Line(plenty, null, 10), new Line(scarce, null, 5)), null))
                .hasMessageContaining(scarce + ": requested 5, only 3 available");
        assertThat(available(plenty)).isEqualTo(100);   // the first line was rolled back too
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameReservation() {
        var sku = newSku("JHB", 10);
        var key = key();
        var first = reservations.reserve(key, "ORD-4", List.of(new Line(sku, null, 4)), null);
        var second = reservations.reserve(key, "ORD-4", List.of(new Line(sku, null, 4)), null);
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(available(sku)).isEqualTo(6);
    }

    @Test
    void picksTheWarehouseThatCanFillTheWholeLine() {
        var sku = newSku("JHB", 8);
        stock.receive(sku, "DBN", 30);
        stock.receive(sku, "CPT", 12);
        var r = reservations.reserve(key(), "ORD-5", List.of(new Line(sku, null, 10)), null);
        assertThat(r.getLines().get(0).getStockItem().getWarehouse()).isEqualTo("DBN");

        // A specific warehouse is respected even if another has more
        var r2 = reservations.reserve(key(), "ORD-6", List.of(new Line(sku, "CPT", 10)), null);
        assertThat(r2.getLines().get(0).getStockItem().getWarehouse()).isEqualTo("CPT");
    }

    @Test
    void abandonedHoldsExpire() {
        var sku = newSku("JHB", 10);
        var r = reservations.reserve(key(), "ORD-7", List.of(new Line(sku, null, 10)), 15);
        clock.advance(Duration.ofMinutes(16));

        assertThat(reservations.expireStaleHolds()).isGreaterThanOrEqualTo(1);
        assertThat(available(sku)).isEqualTo(10);
        assertThat(reservations.get(r.getId()).getStatus()).isEqualTo(Reservation.Status.EXPIRED);
        assertThatThrownBy(() -> reservations.confirm(r.getId())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotConfirmAfterExpiryEvenIfTheJobHasNotRunYet() {
        var sku = newSku("JHB", 10);
        var r = reservations.reserve(key(), "ORD-8", List.of(new Line(sku, null, 1)), 5);
        clock.advance(Duration.ofMinutes(6));
        assertThatThrownBy(() -> reservations.confirm(r.getId())).hasMessageContaining("expired");
    }

    @Test
    void confirmTwiceIsHarmless() {
        var sku = newSku("JHB", 10);
        var r = reservations.reserve(key(), "ORD-9", List.of(new Line(sku, null, 3)), null);
        reservations.confirm(r.getId());
        reservations.confirm(r.getId());
        assertThat(stock.levels(sku).get(0).onHand()).isEqualTo(7);
    }

    @Test
    void neverOversellsUnderConcurrentCheckouts() throws Exception {
        var sku = newSku("JHB", 10);
        int buyers = 24;
        var start = new CountDownLatch(1);
        var succeeded = new AtomicInteger();
        var rejected = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<Callable<Void>>();
            for (int i = 0; i < buyers; i++) {
                int n = i;
                tasks.add(() -> {
                    start.await();
                    try {
                        reservations.reserve(key(), "RUSH-" + n, List.of(new Line(sku, null, 1)), null);
                        succeeded.incrementAndGet();
                    } catch (RuntimeException e) {
                        rejected.incrementAndGet();
                    }
                    return null;
                });
            }
            var futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            for (var f : futures) f.get();
        }

        assertThat(succeeded.get()).isEqualTo(10);
        assertThat(rejected.get()).isEqualTo(buyers - 10);
        var level = stock.levels(sku).get(0);
        assertThat(level.reserved()).isEqualTo(10);
        assertThat(level.available()).isZero();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
