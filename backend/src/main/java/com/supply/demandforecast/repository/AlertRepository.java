package com.supply.demandforecast.repository;

import com.supply.demandforecast.domain.AlertType;
import com.supply.demandforecast.domain.Severity;
import com.supply.demandforecast.entity.Alert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AlertRepository extends JpaRepository<Alert, Long> {

    /**
     * The open alert for a (sku, type), if there is one.
     *
     * <p>The scan calls this before writing, so a standing problem is restated rather than
     * duplicated. {@code Alert}'s unique index is what actually guarantees at most one row
     * comes back.
     */
    Optional<Alert> findBySkuAndTypeAndResolvedFalse(String sku, AlertType type);

    /**
     * Every open alert, newest first. Used by the scan to find alerts it did <em>not</em>
     * restate this pass, which are the ones whose underlying problem has gone away.
     */
    List<Alert> findByResolvedFalse();

    /**
     * Filtered query behind {@code GET /api/alerts}, with every filter optional.
     *
     * <p>Written as one query with null-guarded predicates rather than as a {@code
     * Specification}: with three filters the combinatorial derived-method names would be
     * unreadable, and a Specification builder is more machinery than three {@code or} clauses
     * justify. Hibernate elides a {@code :param is null} branch for a bound null, so an
     * unfiltered call still plans against the indexes.
     *
     * <p>Sorted by severity then score so the caller's first page is the urgent one, without
     * relying on the client to sort. {@code Severity} is persisted as a string, so ordering
     * by the column would put CRITICAL after MEDIUM alphabetically — hence the explicit
     * {@code case} ranking rather than {@code order by a.severity}.
     */
    @Query("""
            select a from Alert a
            where (:severity is null or a.severity = :severity)
              and (:type is null or a.type = :type)
              and (:resolved is null or a.resolved = :resolved)
              and (:sku is null or a.sku = :sku)
            order by case a.severity
                       when com.supply.demandforecast.domain.Severity.CRITICAL then 0
                       when com.supply.demandforecast.domain.Severity.HIGH then 1
                       when com.supply.demandforecast.domain.Severity.MEDIUM then 2
                       else 3
                     end,
                     a.riskScore desc,
                     a.updatedAt desc
            """)
    Page<Alert> search(@Param("severity") Severity severity,
                       @Param("type") AlertType type,
                       @Param("resolved") Boolean resolved,
                       @Param("sku") String sku,
                       Pageable pageable);

    long countByResolvedFalse();

    long countByResolvedFalseAndSeverity(Severity severity);
}
