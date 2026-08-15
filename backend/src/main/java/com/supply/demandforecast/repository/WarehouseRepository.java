package com.supply.demandforecast.repository;

import com.supply.demandforecast.entity.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    Optional<Warehouse> findByCode(String code);

    /** Column order for the heatmap: stable across reloads, and readable. */
    List<Warehouse> findByActiveTrueOrderByCodeAsc();

    List<Warehouse> findAllByOrderByCodeAsc();
}
