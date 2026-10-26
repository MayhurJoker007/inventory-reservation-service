package dev.mayhur.inventory.reservation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    Optional<Reservation> findByIdempotencyKey(String key);

    List<Reservation> findTop200ByStatusAndExpiresAtBefore(Reservation.Status status, Instant now);
}
