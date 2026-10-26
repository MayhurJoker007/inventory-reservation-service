package dev.mayhur.inventory.reservation;

import dev.mayhur.inventory.stock.StockItem;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "reservation_line")
public class ReservationLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "stock_item_id")
    private StockItem stockItem;

    private int quantity;

    protected ReservationLine() {
    }

    ReservationLine(Reservation reservation, StockItem stockItem, int quantity) {
        this.reservation = reservation;
        this.stockItem = stockItem;
        this.quantity = quantity;
    }

    public StockItem getStockItem() { return stockItem; }
    public int getQuantity() { return quantity; }
}
