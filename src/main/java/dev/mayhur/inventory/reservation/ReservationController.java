package dev.mayhur.inventory.reservation;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.mayhur.inventory.stock.StockService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ReservationController {

    public record LineRequest(@NotBlank String sku, String warehouse, @Positive int quantity) {
    }

    public record ReserveRequest(@NotBlank String orderRef, @NotEmpty List<@Valid LineRequest> lines,
                                 @Min(1) @Max(240) Integer holdMinutes) {
    }

    public record ReceiveRequest(@NotBlank String warehouse, @Positive int quantity) {
    }

    public record LineView(String sku, String warehouse, int quantity) {
    }

    public record ReservationView(UUID id, String orderRef, Reservation.Status status, Instant expiresAt,
                                  List<LineView> lines) {
        static ReservationView of(Reservation r) {
            return new ReservationView(r.getId(), r.getOrderRef(), r.getStatus(), r.getExpiresAt(),
                    r.getLines().stream()
                            .map(l -> new LineView(l.getStockItem().getSku(), l.getStockItem().getWarehouse(), l.getQuantity()))
                            .toList());
        }
    }

    private final ReservationService reservations;
    private final StockService stock;

    public ReservationController(ReservationService reservations, StockService stock) {
        this.reservations = reservations;
        this.stock = stock;
    }

    /** The client sends an Idempotency-Key so a timed-out request can be retried without holding stock twice. */
    @PostMapping("/reservations")
    public ResponseEntity<ReservationView> reserve(@RequestHeader("Idempotency-Key") String key,
                                                   @Valid @RequestBody ReserveRequest request) {
        var lines = request.lines().stream()
                .map(l -> new ReservationService.Line(l.sku(), l.warehouse(), l.quantity()))
                .toList();
        var r = reservations.reserve(key, request.orderRef(), lines, request.holdMinutes());
        return ResponseEntity.created(URI.create("/api/reservations/" + r.getId())).body(ReservationView.of(r));
    }

    @GetMapping("/reservations/{id}")
    public ReservationView get(@PathVariable UUID id) {
        return ReservationView.of(reservations.get(id));
    }

    @PostMapping("/reservations/{id}/confirm")
    public ReservationView confirm(@PathVariable UUID id) {
        return ReservationView.of(reservations.confirm(id));
    }

    @PostMapping("/reservations/{id}/release")
    public ReservationView release(@PathVariable UUID id) {
        return ReservationView.of(reservations.release(id));
    }

    @GetMapping("/stock/{sku}")
    public List<StockService.Level> levels(@PathVariable String sku) {
        return stock.levels(sku);
    }

    @PostMapping("/stock/{sku}/receive")
    public StockService.Level receive(@PathVariable String sku, @Valid @RequestBody ReceiveRequest request) {
        return stock.receive(sku, request.warehouse(), request.quantity());
    }

    @ExceptionHandler(ReservationRejectedException.class)
    ProblemDetail rejected(ReservationRejectedException e) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Not enough stock");
        problem.setProperty("problems", e.problems());
        return problem;
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    ProblemDetail notFound(ReservationNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({IllegalStateException.class, ConcurrencyConflictException.class})
    ProblemDetail conflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
