package dev.mayhur.inventory.stock;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockService {

    public record Level(String sku, String warehouse, int onHand, int reserved, int available) {
        static Level of(StockItem s) {
            return new Level(s.getSku(), s.getWarehouse(), s.getOnHand(), s.getReserved(), s.available());
        }
    }

    private final StockRepository stock;

    public StockService(StockRepository stock) {
        this.stock = stock;
    }

    @Transactional(readOnly = true)
    public List<Level> levels(String sku) {
        return stock.findBySkuOrderByWarehouse(sku).stream().map(Level::of).toList();
    }

    /** Goods received at a warehouse. Creates the stock row the first time a SKU arrives there. */
    @Transactional
    public Level receive(String sku, String warehouse, int qty) {
        var item = stock.findBySkuAndWarehouse(sku, warehouse).orElseGet(() -> stock.save(new StockItem(sku, warehouse, 0)));
        item.receive(qty);
        return Level.of(stock.saveAndFlush(item));
    }
}
