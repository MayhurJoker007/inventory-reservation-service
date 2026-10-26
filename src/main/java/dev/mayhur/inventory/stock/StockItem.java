package dev.mayhur.inventory.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Stock of one SKU in one warehouse. "Reserved" is stock promised to a checkout that hasn't been confirmed yet;
 * what can still be sold is on hand minus reserved.
 *
 * The @Version column is what stops two checkouts from both taking the last case: whichever commits second
 * gets an optimistic lock failure and is retried against the fresh numbers.
 */
@Entity
@Table(name = "stock_item")
public class StockItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sku;

    private String warehouse;

    @Column(name = "on_hand")
    private int onHand;

    private int reserved;

    @Version
    private long version;

    protected StockItem() {
    }

    public StockItem(String sku, String warehouse, int onHand) {
        this.sku = sku;
        this.warehouse = warehouse;
        this.onHand = onHand;
    }

    public int available() {
        return onHand - reserved;
    }

    public void reserve(int qty) {
        if (qty > available()) {
            throw new InsufficientStockException(sku, warehouse, qty, available());
        }
        reserved += qty;
    }

    public void release(int qty) {
        reserved -= qty;
    }

    /** A confirmed reservation becomes a real deduction from stock. */
    public void commit(int qty) {
        reserved -= qty;
        onHand -= qty;
    }

    public void receive(int qty) {
        onHand += qty;
    }

    public Long getId() { return id; }
    public String getSku() { return sku; }
    public String getWarehouse() { return warehouse; }
    public int getOnHand() { return onHand; }
    public int getReserved() { return reserved; }
}
