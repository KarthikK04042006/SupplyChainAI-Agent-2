package com.supply.demandforecast.repository;

import com.supply.demandforecast.entity.ForecastAccuracy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ForecastAccuracyRepository extends JpaRepository<ForecastAccuracy, Long> {

    Optional<ForecastAccuracy> findBySku(String sku);

    /** Used by the scan to upsert a whole catalogue's scores without a query per SKU. */
    List<ForecastAccuracy> findBySkuIn(Collection<String> skus);

    /**
     * Mean MAPE across every scored SKU, which the KPI turns into an accuracy percentage.
     *
     * <p>Rows with a null MAPE are skipped by {@code avg}, so the figure describes the SKUs
     * actually measured — {@link #countByMapeNotNull()} is what makes that sample size visible
     * instead of implied.
     */
    @Query("select avg(a.mape) from ForecastAccuracy a where a.mape is not null")
    Double averageMape();

    long countByMapeNotNull();

    @Query("select max(a.scoredAt) from ForecastAccuracy a")
    Instant lastScoredAt();
}
