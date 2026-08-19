package com.supply.demandforecast.service;

import com.opencsv.CSVReaderHeaderAware;
import com.opencsv.exceptions.CsvValidationException;
import com.supply.demandforecast.dto.ImportResult;
import com.supply.demandforecast.entity.InventoryLevel;
import com.supply.demandforecast.entity.Product;
import com.supply.demandforecast.entity.Warehouse;
import com.supply.demandforecast.repository.InventoryLevelRepository;
import com.supply.demandforecast.repository.ProductRepository;
import com.supply.demandforecast.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imports per-warehouse stock levels from CSV.
 *
 * <p>Expects {@code sku}, {@code warehouse} and {@code quantity_on_hand}; {@code on_order},
 * {@code warehouse_name} and {@code region} are optional. Warehouses are created on first sight
 * rather than declared up front — the set of locations is a property of the file, and a separate
 * registration step is one more thing that could disagree with the stock data.
 *
 * <p>Recomputes {@link Product#getCurrentStock()} and {@code onOrder} from the imported rows,
 * because the risk scan and the KPI cards read the product-level totals. Leaving those stale
 * would let the heatmap and the alert list describe different inventories.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryImportService {

    private static final String COL_SKU = "sku";
    private static final String COL_WAREHOUSE = "warehouse";
    private static final String COL_WAREHOUSE_NAME = "warehouse_name";
    private static final String COL_REGION = "region";
    private static final String COL_ON_HAND = "quantity_on_hand";
    private static final String COL_ON_ORDER = "on_order";

    /**
     * Cap on the per-row messages carried back, so a wholly malformed file cannot exhaust memory.
     *
     * <p>Caps the list only. The rejected <em>count</em> is tallied separately and reported in
     * full, so a caller can tell "100 bad rows" from "the first 100 of 4,000".
     */
    private static final int MAX_REPORTED_ERRORS = 100;

    /**
     * Cap on data rows processed in one upload, bounding memory and work for a file that is small
     * on disk but pathologically long. Exceeding it is a 413, not a per-row rejection.
     */
    private static final int MAX_ROWS = 50_000;

    /**
     * Joins SKU and warehouse code into the dedup key for one upload's batch.
     *
     * <p>A NUL cannot appear in either field, so the pair cannot collide with a different pair that
     * happens to concatenate the same way. Written as the escape {@code '\0'} rather than the literal
     * byte: an embedded NUL makes every tool that sniffs content — {@code git diff} included — treat
     * this source file as binary. The key is only ever used for lookup and never split apart.
     */
    private static final char KEY_SEPARATOR = '\0';

    private final InventoryLevelRepository inventoryLevelRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductRepository productRepository;

    @Transactional
    public ImportResult importCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new CsvImportException("Uploaded file is empty");
        }

        List<String> errors = new ArrayList<>();
        // Keyed by sku + warehouse so a file listing the same cell twice collapses to its last
        // row instead of racing itself on the unique constraint.
        Map<String, Row> batch = new LinkedHashMap<>();
        int rowNumber = 1;
        int parsed = 0;
        int rejected = 0;

        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVReaderHeaderAware csv = new CSVReaderHeaderAware(reader)) {

            Map<String, String> row;
            while ((row = csv.readMap()) != null) {
                rowNumber++;
                if (rowNumber - 1 > MAX_ROWS) {
                    throw new PayloadTooLargeException(
                            "CSV exceeds the %,d-row limit".formatted(MAX_ROWS));
                }
                try {
                    Row parsedRow = toRow(row);
                    batch.put(parsedRow.sku() + KEY_SEPARATOR + parsedRow.warehouseCode(), parsedRow);
                    parsed++;
                } catch (IllegalArgumentException e) {
                    // Counted always, listed only up to the cap.
                    rejected++;
                    if (errors.size() < MAX_REPORTED_ERRORS) {
                        errors.add("Row %d: %s".formatted(rowNumber, e.getMessage()));
                    }
                }
            }
        } catch (IOException | CsvValidationException e) {
            throw new CsvImportException("Could not read CSV: " + e.getMessage(), e);
        }

        int updated = persist(batch.values());
        int inserted = batch.size() - updated;

        log.info("Inventory import: {} rows parsed, {} inserted, {} updated, {} rejected",
                parsed, inserted, updated, rejected);

        return new ImportResult(parsed, inserted, updated, rejected, errors);
    }

    private Row toRow(Map<String, String> row) {
        return new Row(
                CsvValues.required(row, COL_SKU),
                CsvValues.required(row, COL_WAREHOUSE),
                CsvValues.optional(row, COL_WAREHOUSE_NAME),
                CsvValues.optional(row, COL_REGION),
                CsvValues.requiredDouble(row, COL_ON_HAND),
                CsvValues.optionalDouble(row, COL_ON_ORDER));
    }

    /** Upserts levels and their warehouses, returning how many rows updated an existing cell. */
    private int persist(Iterable<Row> rows) {
        Map<String, Warehouse> warehouses = new HashMap<>();
        List<InventoryLevel> toSave = new ArrayList<>();
        List<String> touchedSkus = new ArrayList<>();
        int updated = 0;

        for (Row row : rows) {
            Warehouse warehouse = warehouses.computeIfAbsent(
                    row.warehouseCode(), code -> upsertWarehouse(code, row));

            InventoryLevel level = inventoryLevelRepository
                    .findBySkuAndWarehouseId(row.sku(), warehouse.getId())
                    .orElse(null);

            if (level == null) {
                level = InventoryLevel.builder()
                        .sku(row.sku())
                        .warehouse(warehouse)
                        .build();
            } else {
                updated++;
            }

            level.setQuantityOnHand(row.onHand());
            if (row.onOrder() != null) {
                level.setOnOrder(row.onOrder());
            }

            toSave.add(level);
            touchedSkus.add(row.sku());
        }

        inventoryLevelRepository.saveAll(toSave);
        // Flushed before the aggregate is recomputed, since it sums these rows in the database.
        inventoryLevelRepository.flush();
        syncProductTotals(touchedSkus);

        return updated;
    }

    /**
     * Creates a warehouse on first sight, or fills in a name and region it did not have.
     *
     * <p>Existing values are not overwritten: a file that omits the optional columns should not
     * blank out details an earlier upload established.
     */
    private Warehouse upsertWarehouse(String code, Row row) {
        Warehouse warehouse = warehouseRepository.findByCode(code).orElse(null);

        if (warehouse == null) {
            return warehouseRepository.save(Warehouse.builder()
                    .code(code)
                    .name(row.warehouseName() == null ? code : row.warehouseName())
                    .region(row.region())
                    .build());
        }

        boolean changed = false;
        if (row.warehouseName() != null && !row.warehouseName().equals(warehouse.getName())) {
            warehouse.setName(row.warehouseName());
            changed = true;
        }
        if (row.region() != null && warehouse.getRegion() == null) {
            warehouse.setRegion(row.region());
            changed = true;
        }
        return changed ? warehouseRepository.save(warehouse) : warehouse;
    }

    /**
     * Restates each touched product's catalogue-wide stock from its per-warehouse rows.
     *
     * <p>A SKU with stock but no product record is skipped rather than invented: the catalogue
     * carries lead times and costs that a stock file cannot supply, and a product with a
     * zero-day lead time would quietly distort every forecast it appears in. The levels are
     * still stored, so the row starts working the moment the catalogue file lands.
     */
    private void syncProductTotals(List<String> skus) {
        List<String> distinct = skus.stream().distinct().toList();
        List<Product> products = productRepository.findBySkuIn(distinct);

        for (Product product : products) {
            product.setCurrentStock(inventoryLevelRepository.sumOnHandBySku(product.getSku()));
            product.setOnOrder(inventoryLevelRepository.sumOnOrderBySku(product.getSku()));
        }

        productRepository.saveAll(products);

        int orphans = distinct.size() - products.size();
        if (orphans > 0) {
            log.info("{} SKUs had stock imported but no product record yet", orphans);
        }
    }

    /** One validated CSV row. */
    private record Row(
            String sku,
            String warehouseCode,
            String warehouseName,
            String region,
            double onHand,
            Double onOrder
    ) {
    }
}
