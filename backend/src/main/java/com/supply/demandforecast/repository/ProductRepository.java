package com.supply.demandforecast.repository;

import com.supply.demandforecast.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySku(String sku);

    boolean existsBySku(String sku);

    List<Product> findByCategory(String category);

    /** Used by the catalogue import to upsert a whole file without a query per row. */
    List<Product> findBySkuIn(Collection<String> skus);

    /**
     * Catalogue search for the product list and the heatmap.
     *
     * <p>Both filters are optional and combine with AND, following the same null-means-unset
     * shape as {@code AlertRepository.search}. {@code q} matches SKU or name, case-insensitively,
     * because a planner searching "widget" has no reason to know which column holds it.
     */
    @Query("""
            select p from Product p
            where (:q is null or lower(p.sku) like lower(concat('%', :q, '%'))
                              or lower(p.name) like lower(concat('%', :q, '%')))
              and (:category is null or p.category = :category)
            """)
    Page<Product> search(@Param("q") String q,
                         @Param("category") String category,
                         Pageable pageable);

    /** Distinct categories for the heatmap's filter dropdown, blanks excluded. */
    @Query("""
            select distinct p.category from Product p
            where p.category is not null and p.category <> ''
            order by p.category
            """)
    List<String> findDistinctCategories();

    /**
     * Total value of priced stock.
     *
     * <p>Summed in the database rather than by loading the catalogue: the KPI is one number,
     * and a real catalogue is not something to pull into memory to add up.
     *
     * <p>Unpriced SKUs are excluded rather than counted as zero, and {@link
     * #countByUnitCostIsNull()} reports how many were left out, so a half-priced catalogue
     * cannot show a confident total that understates by half.
     */
    @Query("""
            select coalesce(sum(p.currentStock * p.unitCost), 0)
            from Product p where p.unitCost is not null
            """)
    double sumInventoryValue();

    long countByUnitCostNotNull();

    long countByUnitCostIsNull();
}
