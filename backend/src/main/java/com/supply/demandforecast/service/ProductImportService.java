package com.supply.demandforecast.service;

import com.opencsv.CSVReaderHeaderAware;
import com.opencsv.exceptions.CsvValidationException;
import com.supply.demandforecast.dto.ImportResult;
import com.supply.demandforecast.entity.Product;
import com.supply.demandforecast.repository.ProductRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imports the product catalogue from CSV.
 *
 * <p>Expects a header row with {@code sku} and {@code name}; {@code category},
 * {@code lead_time_days}, {@code reorder_point}, {@code safety_stock}, {@code unit_cost} and
 * {@code current_stock} are optional. Column order does not matter.
 *
 * <p>Follows {@link DemandImportService} deliberately — same per-row error collection, same
 * {@link ImportResult} shape, same idempotent upsert. One upload page renders all three
 * importers, so a second reporting idiom would mean a second error renderer.
 *
 * <p>An existing SKU is updated rather than duplicated, and <em>only for the columns present in
 * the file</em>. A catalogue file listing lead times must not silently zero the stock levels a
 * separate inventory upload established, so an absent column leaves the stored value alone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImportService {

    private static final String COL_SKU = "sku";
    private static final String COL_NAME = "name";
    private static final String COL_CATEGORY = "category";
    private static final String COL_LEAD_TIME = "lead_time_days";
    private static final String COL_REORDER_POINT = "reorder_point";
    private static final String COL_SAFETY_STOCK = "safety_stock";
    private static final String COL_UNIT_COST = "unit_cost";
    private static final String COL_CURRENT_STOCK = "current_stock";

    /** Cap on reported errors, so a wholly malformed file cannot exhaust memory. */
    private static final int MAX_REPORTED_ERRORS = 100;

    /**
     * Cap on data rows processed in one upload, bounding memory and work for a file that is small
     * on disk but pathologically long. Exceeding it is a 413, not a per-row rejection.
     */
    private static final int MAX_ROWS = 50_000;

    private final ProductRepository productRepository;

    @Transactional
    public ImportResult importCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new CsvImportException("Uploaded file is empty");
        }

        List<String> errors = new ArrayList<>();
        // Keyed by SKU so a file listing the same product twice collapses to its last row
        // rather than racing itself on the unique constraint.
        Map<String, Map<String, String>> batch = new LinkedHashMap<>();
        int rowNumber = 1;
        int parsed = 0;

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
                    validate(row);
                    batch.put(CsvValues.required(row, COL_SKU), row);
                    parsed++;
                } catch (IllegalArgumentException e) {
                    if (errors.size() < MAX_REPORTED_ERRORS) {
                        errors.add("Row %d: %s".formatted(rowNumber, e.getMessage()));
                    }
                }
            }
        } catch (IOException | CsvValidationException e) {
            throw new CsvImportException("Could not read CSV: " + e.getMessage(), e);
        }

        int updated = persist(batch);
        int inserted = batch.size() - updated;

        log.info("Product import: {} rows parsed, {} inserted, {} updated, {} rejected",
                parsed, inserted, updated, errors.size());

        return new ImportResult(parsed, inserted, updated, errors.size(), errors);
    }

    /**
     * Parses every field so a bad row is rejected with its line number rather than failing
     * later, mid-batch, with no way to say which line was at fault.
     */
    private void validate(Map<String, String> row) {
        CsvValues.required(row, COL_SKU);
        CsvValues.required(row, COL_NAME);
        CsvValues.optionalInt(row, COL_LEAD_TIME);
        CsvValues.optionalInt(row, COL_REORDER_POINT);
        CsvValues.optionalDouble(row, COL_SAFETY_STOCK);
        CsvValues.optionalDouble(row, COL_UNIT_COST);
        CsvValues.optionalDouble(row, COL_CURRENT_STOCK);
    }

    /** Upserts the batch, returning how many rows updated an existing product. */
    private int persist(Map<String, Map<String, String>> batch) {
        List<Product> existing = productRepository.findBySkuIn(batch.keySet());
        Map<String, Product> bySku = new LinkedHashMap<>();
        existing.forEach(product -> bySku.put(product.getSku(), product));

        List<Product> toSave = new ArrayList<>();
        int updated = 0;

        for (Map.Entry<String, Map<String, String>> entry : batch.entrySet()) {
            Map<String, String> row = entry.getValue();
            Product product = bySku.get(entry.getKey());

            if (product == null) {
                product = Product.builder().sku(entry.getKey()).build();
            } else {
                updated++;
            }

            apply(product, row);
            toSave.add(product);
        }

        productRepository.saveAll(toSave);
        return updated;
    }

    /** Copies present columns onto the product, leaving absent ones untouched. */
    private void apply(Product product, Map<String, String> row) {
        product.setName(CsvValues.required(row, COL_NAME));

        String category = CsvValues.optional(row, COL_CATEGORY);
        if (category != null) {
            product.setCategory(category);
        }

        Integer leadTime = CsvValues.optionalInt(row, COL_LEAD_TIME);
        if (leadTime != null) {
            product.setLeadTimeDays(leadTime);
        }

        Integer reorderPoint = CsvValues.optionalInt(row, COL_REORDER_POINT);
        if (reorderPoint != null) {
            product.setReorderPoint(reorderPoint);
        }

        Double safetyStock = CsvValues.optionalDouble(row, COL_SAFETY_STOCK);
        if (safetyStock != null) {
            product.setSafetyStock(safetyStock);
        }

        Double unitCost = CsvValues.optionalDouble(row, COL_UNIT_COST);
        if (unitCost != null) {
            product.setUnitCost(unitCost);
        }

        // Accepted for a catalogue that carries opening stock, but the inventory import is the
        // real source: it recomputes this field from the per-warehouse rows and will overwrite
        // whatever a catalogue file put here.
        Double currentStock = CsvValues.optionalDouble(row, COL_CURRENT_STOCK);
        if (currentStock != null) {
            product.setCurrentStock(currentStock);
        }
    }
}
