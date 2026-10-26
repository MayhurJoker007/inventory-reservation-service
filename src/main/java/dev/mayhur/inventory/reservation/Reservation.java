package dev.mayhur.inventory.reservation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "reservation")
public class Reservation {

    public enum Status { HELD, CONFIRMED, RELEASED, EXPIRED }

    @Id
    private UUID id;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "order_ref")
    private String orderRef;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<ReservationLine> lines = new ArrayList<>();

    protected Reservation() {
    }

    Reservation(String idempotencyKey, String orderRef, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.idempotencyKey = idempotencyKey;
        this.orderRef = orderRef;
        this.status = Status.HELD;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    void addLine(ReservationLine line) {
        lines.add(line);
    }

    void close(Status newStatus, Instant at) {
        if (status != Status.HELD) {
            throw new IllegalStateException("Reservation " + id + " is already " + status);
        }
        status = newStatus;
        closedAt = at;
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getOrderRef() { return orderRef; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getClosedAt() { return closedAt; }
    public List<ReservationLine> getLines() { return lines; }
}
