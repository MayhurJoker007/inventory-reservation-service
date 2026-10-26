package dev.mayhur.inventory.reservation;

import java.util.List;
import java.util.UUID;

class ReservationRejectedException extends RuntimeException {

    private final List<String> problems;

    ReservationRejectedException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    List<String> problems() {
        return problems;
    }
}

class ReservationNotFoundException extends RuntimeException {

    ReservationNotFoundException(UUID id) {
        super("Reservation " + id + " not found");
    }
}

class ConcurrencyConflictException extends RuntimeException {

    ConcurrencyConflictException(String what) {
        super("Too much contention to " + what + ", please retry");
    }
}
