package dev.mayhur.inventory.stock;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StockRepository extends JpaRepository<StockItem, Long> {

    List<StockItem> findBySkuOrderByWarehouse(String sku);

    Optional<StockItem> findBySkuAndWarehouse(String sku, String warehouse);
}
