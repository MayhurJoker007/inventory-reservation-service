package dev.mayhur.inventory.reservation;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final int MAX_ATTEMPTS = 5;

    public record Line(String sku, String warehouse, int quantity) {
    }

    private final ReservationTransactions tx;
    private final Duration defaultHold;

    public ReservationService(ReservationTransactions tx,
                              @Value("${inventory.default-hold-minutes:15}") long holdMinutes) {
        this.tx = tx;
        this.defaultHold = Duration.ofMinutes(holdMinutes);
    }

    public Reservation reserve(String idempotencyKey, String orderRef, List<Line> lines, Integer holdMinutes) {
        var hold = holdMinutes == null ? defaultHold : Duration.ofMinutes(holdMinutes);
        var requests = lines.stream()
                .map(l -> new ReservationTransactions.LineRequest(l.sku(), l.warehouse(), l.quantity()))
                .toList();
        return withRetry("reserve " + orderRef, () -> {
            try {
                return tx.reserve(idempotencyKey, orderRef, requests, hold);
            } catch (DataIntegrityViolationException e) {
                // Two requests with the same idempotency key raced; the other one won, so return its result
                return tx.reserveLookup(idempotencyKey).orElseThrow(() -> e);
            }
        });
    }

    public Reservation confirm(UUID id) {
        return withRetry("confirm " + id, () -> tx.confirm(id));
    }

    public Reservation release(UUID id) {
        return withRetry("release " + id, () -> tx.release(id, Reservation.Status.RELEASED));
    }

    public Reservation get(UUID id) {
        return tx.find(id);
    }

    /** Checkouts that were abandoned give their stock back. */
    @Scheduled(fixedDelayString = "${inventory.expiry-check-ms:30000}")
    public int expireStaleHolds() {
        int count = 0;
        for (var id : tx.expiredHolds()) {
            try {
                withRetry("expire " + id, () -> tx.release(id, Reservation.Status.EXPIRED));
                count++;
            } catch (IllegalStateException e) {
                // confirmed between the query and now; nothing to do
            }
        }
        if (count > 0) {
            log.info("Expired {} reservation(s)", count);
        }
        return count;
    }

    /**
     * Optimistic locking means a busy SKU can lose the race; the fix is simply to try again with fresh data.
     * Small randomised backoff so retries from many threads don't collide again in lockstep.
     */
    private <T> T withRetry(String what, Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new ConcurrencyConflictException(what);
                }
                try {
                    Thread.sleep((long) (Math.random() * 15 * attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ConcurrencyConflictException(what);
                }
            }
        }
    }
}
