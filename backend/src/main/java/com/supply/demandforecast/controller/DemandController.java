package com.supply.demandforecast.controller;

import com.supply.demandforecast.dto.ImportResult;
import com.supply.demandforecast.service.DemandImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Bulk ingest of demand history. */
@RestController
@RequestMapping("/api/demand")
@RequiredArgsConstructor
public class DemandController {

    private final DemandImportService demandImportService;

    /**
     * Imports demand history from a CSV with {@code sku,date,quantity} columns.
     *
     * <p>Re-importing the same file is safe — existing days are updated, not duplicated.
     */
    @PostMapping("/import")
    public ImportResult importCsv(@RequestParam("file") MultipartFile file) {
        return demandImportService.importCsv(file);
    }
}
