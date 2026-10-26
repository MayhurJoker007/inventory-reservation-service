package dev.mayhur.inventory.reservation;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.mayhur.inventory.stock.InsufficientStockException;
import dev.mayhur.inventory.stock.StockItem;
import dev.mayhur.inventory.stock.StockRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each method is one database transaction. They're kept separate from {@link ReservationService} so that a
 * retry after an optimistic lock failure starts a fresh transaction with fresh data.
 */
@Component
class ReservationTransactions {

    record LineRequest(String sku, String warehouse, int quantity) {
    }

    private final ReservationRepository reservations;
    private final StockRepository stock;
    private final Clock clock;

    ReservationTransactions(ReservationRepository reservations, StockRepository stock, Clock clock) {
        this.reservations = reservations;
        this.stock = stock;
        this.clock = clock;
    }

    @Transactional
    Reservation reserve(String idempotencyKey, String orderRef, List<LineRequest> lines, Duration hold) {
        var existing = reservations.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        var now = clock.instant();
        var reservation = new Reservation(idempotencyKey, orderRef, now, now.plus(hold));
        var problems = new ArrayList<String>();

        // All or nothing: check and hold every line, and if any can't be filled the transaction rolls back
        for (var line : lines) {
            try {
                var item = pick(line);
                item.reserve(line.quantity());
                reservation.addLine(new ReservationLine(reservation, item, line.quantity()));
            } catch (InsufficientStockException e) {
                problems.add(e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            throw new ReservationRejectedException(problems);
        }
        return reservations.saveAndFlush(reservation);
    }

    /**
     * Without a warehouse, take the line from the warehouse with the most available that can fill it on its own.
     * Splitting one line across warehouses means two deliveries, which buyers don't want.
     */
    private StockItem pick(LineRequest line) {
        if (line.warehouse() != null) {
            return stock.findBySkuAndWarehouse(line.sku(), line.warehouse())
                    .orElseThrow(() -> new InsufficientStockException(line.sku(), line.warehouse(), line.quantity(), 0));
        }
        var candidates = stock.findBySkuOrderByWarehouse(line.sku());
        return candidates.stream()
                .filter(s -> s.available() >= line.quantity())
                .max(Comparator.comparingInt(StockItem::available))
                .orElseThrow(() -> new InsufficientStockException(line.sku(), null, line.quantity(),
                        candidates.stream().mapToInt(StockItem::available).max().orElse(0)));
    }

    @Transactional(readOnly = true)
    Optional<Reservation> reserveLookup(String idempotencyKey) {
        return reservations.findByIdempotencyKey(idempotencyKey);
    }

    @Transactional
    Reservation confirm(UUID id) {
        var r = find(id);
        if (r.getStatus() == Reservation.Status.CONFIRMED) {
            return r; // confirming twice (a retried payment callback) is harmless
        }
        if (r.getStatus() == Reservation.Status.HELD && r.getExpiresAt().isBefore(clock.instant())) {
            throw new IllegalStateException("Reservation " + id + " expired at " + r.getExpiresAt());
        }
        r.close(Reservation.Status.CONFIRMED, clock.instant());
        r.getLines().forEach(l -> l.getStockItem().commit(l.getQuantity()));
        return r;
    }

    @Transactional
    Reservation release(UUID id, Reservation.Status as) {
        var r = find(id);
        if (r.getStatus() == Reservation.Status.RELEASED || r.getStatus() == Reservation.Status.EXPIRED) {
            return r;
        }
        r.close(as, clock.instant());
        r.getLines().forEach(l -> l.getStockItem().release(l.getQuantity()));
        return r;
    }

    @Transactional(readOnly = true)
    List<UUID> expiredHolds() {
        return reservations.findTop200ByStatusAndExpiresAtBefore(Reservation.Status.HELD, clock.instant())
                .stream().map(Reservation::getId).toList();
    }

    @Transactional(readOnly = true)
    Reservation find(UUID id) {
        return reservations.findById(id).orElseThrow(() -> new ReservationNotFoundException(id));
    }
}
