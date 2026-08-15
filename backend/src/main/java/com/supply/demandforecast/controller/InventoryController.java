package com.supply.demandforecast.controller;

import com.supply.demandforecast.domain.RiskStatus;
import com.supply.demandforecast.dto.ImportResult;
import com.supply.demandforecast.dto.InventoryGrid;
import com.supply.demandforecast.dto.ScanResult;
import com.supply.demandforecast.service.InventoryImportService;
import com.supply.demandforecast.service.InventoryLevelService;
import com.supply.demandforecast.service.InventoryScanService;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Inventory: the per-warehouse grid, the stock upload, and on-demand risk scanning.
 *
 * <p>The scan is exposed under both GET and POST at the same path. GET is what the API was
 * specified with and what a dashboard's refresh button will call; POST is the honest verb for
 * an operation that writes alert rows, and is what a script should use.
 *
 * <p>Either way the endpoint requires MANAGER — see {@code SecurityConfig}. A scan persists
 * alerts, so leaving it under the blanket VIEWER rule for GETs would have made "read-only"
 * untrue for the one GET in the API that writes.
 */
@Validated
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryScanService inventoryScanService;
    private final InventoryLevelService inventoryLevelService;
    private final InventoryImportService inventoryImportService;

    /**
     * Products × warehouses, with each cell's days of supply and status.
     *
     * <p>The cell status is derived in this backend by allocating demand across locations, not
     * returned by the ML risk scan — {@link InventoryLevelService} explains why, and the response
     * is what the page's legend describes.
     */
    @GetMapping("/levels")
    public InventoryGrid levels(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String warehouse,
            @RequestParam(required = false) RiskStatus status,
            @RequestParam(required = false) @Min(0) Integer page,
            @RequestParam(required = false) @Min(1) Integer size) {

        return inventoryLevelService.grid(q, category, warehouse, status, page, size);
    }

    /**
     * Imports per-warehouse stock from CSV. ADMIN only, per {@code SecurityConfig}.
     *
     * <p>Creates warehouses named in the file and recomputes each product's stock aggregate, so
     * this one upload is enough to make the grid, the KPI cards and the next scan agree.
     */
    @PostMapping("/import")
    public ImportResult importCsv(@RequestParam("file") MultipartFile file) {
        return inventoryImportService.importCsv(file);
    }

    /**
     * Scans the catalogue and reconciles the alert table against the result.
     *
     * <p>Returns what changed — opened, updated, resolved — alongside every alert now open, so
     * a caller can render the current state without a follow-up query to {@code /api/alerts}.
     */
    @GetMapping("/scan")
    public ScanResult scan() {
        return inventoryScanService.scan();
    }

    /** Identical to {@link #scan()}; see the class javadoc for why both verbs are mapped. */
    @PostMapping("/scan")
    public ScanResult runScan() {
        return inventoryScanService.scan();
    }
}
