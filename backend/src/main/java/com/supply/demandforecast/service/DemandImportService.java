package com.supply.demandforecast.service;

import com.opencsv.CSVReaderHeaderAware;
import com.opencsv.exceptions.CsvValidationException;
import com.supply.demandforecast.dto.ImportResult;
import com.supply.demandforecast.entity.DemandHistory;
import com.supply.demandforecast.repository.DemandHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Imports demand history from CSV.
 *
 * <p>Expects a header row with {@code sku}, {@code date}, and {@code quantity} columns
 * in any order. Dates are ISO-8601 ({@code yyyy-MM-dd}).
 *
 * <p>Import is idempotent: a row whose {@code (sku, date)} pair already exists updates
 * that row's quantity instead of inserting a duplicate. Malformed rows are collected
 * and reported rather than aborting the batch, so one bad line in a large file does
 * not cost the caller the whole import.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemandImportService {

    private static final String COL_SKU = "sku";
    private static final String COL_DATE = "date";
    private static final String COL_QUANTITY = "quantity";
    /** Cap on reported errors, so a wholly malformed file cannot exhaust memory. */
    private static final int MAX_REPORTED_ERRORS = 100;

    private final DemandHistoryRepository demandHistoryRepository;

    @Transactional
    public ImportResult importCsv(MultipartFile file) {
        if (file.isEmpty()) {
            throw new CsvImportException("Uploaded file is empty");
        }

        List<String> errors = new ArrayList<>();
        // Batch per (sku, date) so duplicates *within* one file collapse too, rather
        // than racing each other on the unique constraint.
        Map<String, DemandHistory> batch = new HashMap<>();
        int rowNumber = 1;
        int parsed = 0;

        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVReaderHeaderAware csv = new CSVReaderHeaderAware(reader)) {

            Map<String, String> row;
            while ((row = csv.readMap()) != null) {
                rowNumber++;
                try {
                    DemandHistory record = toRecord(row);
                    batch.put(record.getSku() + "|" + record.getDemandDate(), record);
                    parsed++;
                } catch (IllegalArgumentException | DateTimeParseException e) {
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

        log.info("CSV import: {} rows parsed, {} inserted, {} updated, {} rejected",
                parsed, inserted, updated, errors.size());

        return new ImportResult(parsed, inserted, updated, errors.size(), errors);
    }

    /** Upserts the batch, returning how many rows updated an existing day. */
    private int persist(Iterable<DemandHistory> records) {
        int updated = 0;
        List<DemandHistory> toSave = new ArrayList<>();

        for (DemandHistory record : records) {
            var existing = demandHistoryRepository
                    .findBySkuAndDemandDate(record.getSku(), record.getDemandDate());
            if (existing.isPresent()) {
                DemandHistory row = existing.get();
                row.setQuantity(record.getQuantity());
                toSave.add(row);
                updated++;
            } else {
                toSave.add(record);
            }
        }

        demandHistoryRepository.saveAll(toSave);
        return updated;
    }

    private DemandHistory toRecord(Map<String, String> row) {
        return DemandHistory.builder()
                .sku(CsvValues.required(row, COL_SKU))
                .demandDate(LocalDate.parse(CsvValues.required(row, COL_DATE)))
                .quantity(CsvValues.requiredDouble(row, COL_QUANTITY))
                .build();
    }
}
