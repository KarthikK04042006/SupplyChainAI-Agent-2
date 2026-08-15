package com.supply.demandforecast.repository;

import com.supply.demandforecast.entity.DemandHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface DemandHistoryRepository extends JpaRepository<DemandHistory, Long> {

    /** History for one SKU, oldest first — the order the ML service expects. */
    List<DemandHistory> findBySkuOrderByDemandDateAsc(String sku);

    /**
     * History for many SKUs in one query, grouped by SKU and oldest first within each.
     *
     * <p>The inventory scan needs every product's history at once. Looping {@link
     * #findBySkuOrderByDemandDateAsc} would issue one query per SKU, which on a real
     * catalogue is the difference between one round trip and hundreds.
     */
    List<DemandHistory> findBySkuInOrderBySkuAscDemandDateAsc(Collection<String> skus);

    List<DemandHistory> findBySkuAndDemandDateGreaterThanEqualOrderByDemandDateAsc(
            String sku, LocalDate from);

    /** Used by CSV ingest to upsert rather than duplicate an existing day. */
    Optional<DemandHistory> findBySkuAndDemandDate(String sku, LocalDate demandDate);

    @Query("select distinct d.sku from DemandHistory d order by d.sku")
    List<String> findDistinctSkus();

    long countBySku(String sku);

    /**
     * Total recent demand per SKU, in one grouped query.
     *
     * <p>The heatmap needs a demand rate for every product it renders. Calling {@link
     * #findBySkuAndDemandDateGreaterThanEqualOrderByDemandDateAsc} per row would issue a query
     * per product and load history the grid never shows, when a sum is all it needs.
     *
     * <p>Returned as an interface projection with explicit aliases rather than {@code Object[]},
     * so the column meaning is carried by the type instead of by a positional index.
     */
    @Query("""
            select d.sku as sku, sum(d.quantity) as total
            from DemandHistory d
            where d.sku in :skus and d.demandDate >= :from
            group by d.sku
            """)
    List<SkuDemandTotal> sumQuantityBySkuSince(@Param("skus") Collection<String> skus,
                                               @Param("from") LocalDate from);

    /** One SKU's summed demand over the queried window. */
    interface SkuDemandTotal {

        String getSku();

        double getTotal();
    }
}
