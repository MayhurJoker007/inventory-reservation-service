package dev.mayhur.inventory.stock;

public class InsufficientStockException extends RuntimeException {

    private final String sku;
    private final int requested;
    private final int available;

    public InsufficientStockException(String sku, String warehouse, int requested, int available) {
        super("%s: requested %d, only %d available%s".formatted(sku, requested, available,
                warehouse == null ? "" : " in " + warehouse));
        this.sku = sku;
        this.requested = requested;
        this.available = available;
    }

    public String getSku() { return sku; }
    public int getRequested() { return requested; }
    public int getAvailable() { return available; }
}
