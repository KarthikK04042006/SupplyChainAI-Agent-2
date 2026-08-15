package com.supply.demandforecast.repository;

import com.supply.demandforecast.entity.InventoryLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface InventoryLevelRepository extends JpaRepository<InventoryLevel, Long> {

    /** Used by the import to upsert rather than duplicate a (sku, warehouse) pair. */
    Optional<InventoryLevel> findBySkuAndWarehouseId(String sku, Long warehouseId);

    List<InventoryLevel> findBySku(String sku);

    /**
     * Every level for the given SKUs, with its warehouse already joined.
     *
     * <p>The fetch join is the point: {@code InventoryLevel.warehouse} is lazy, and the heatmap
     * reads the code of every cell's warehouse. Without it, rendering one page of products
     * would issue a query per cell.
     */
    @Query("""
            select l from InventoryLevel l
            join fetch l.warehouse w
            where l.sku in :skus
            order by l.sku, w.code
            """)
    List<InventoryLevel> findBySkuInWithWarehouse(@Param("skus") Collection<String> skus);

    /** Total on hand for one SKU across locations, used to recompute the product aggregate. */
    @Query("select coalesce(sum(l.quantityOnHand), 0) from InventoryLevel l where l.sku = :sku")
    double sumOnHandBySku(@Param("sku") String sku);

    @Query("select coalesce(sum(l.onOrder), 0) from InventoryLevel l where l.sku = :sku")
    double sumOnOrderBySku(@Param("sku") String sku);

    @Query("select distinct l.sku from InventoryLevel l order by l.sku")
    List<String> findDistinctSkus();

    long countByWarehouseId(Long warehouseId);
}
