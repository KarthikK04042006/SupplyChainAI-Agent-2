package com.supply.demandforecast.controller;

import com.supply.demandforecast.entity.DemandHistory;
import com.supply.demandforecast.entity.InventoryLevel;
import com.supply.demandforecast.entity.Product;
import com.supply.demandforecast.entity.Warehouse;
import com.supply.demandforecast.repository.DemandHistoryRepository;
import com.supply.demandforecast.repository.InventoryLevelRepository;
import com.supply.demandforecast.repository.ProductRepository;
import com.supply.demandforecast.repository.WarehouseRepository;
import com.supply.demandforecast.support.WithTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/inventory/levels}, the products × warehouses heatmap.
 *
 * <p>No ML mock: the cell status is derived in {@code InventoryLevelService} from stored demand,
 * not asked of the risk scan, and that separation is one of the things asserted here. A test that
 * mocked the ML client would hide a regression that started calling it.
 *
 * <p>Demand is seeded relative to today rather than at fixed dates, because the service averages
 * the last 90 days. Fixed 2026-01-01 rows — the idiom the older scan tests use, where the dates
 * only have to be consecutive — would drift out of that window and read as zero demand.
 *
 * <p>The cover figures below assume the {@code test} profile thresholds: a 7-day review period
 * and a 60-day overstock boundary, so a cell is at risk below its product's lead time plus seven.
 * Demand is split evenly across a SKU's stocking locations, which is what lets two cells in one
 * row differ — {@code InventoryLevelService.allocationShare} explains why the stock-weighted
 * alternative cannot.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WithTestUsers.class)
class InventoryGridApiTest {

    /**
     * Days of history written per SKU by {@link #demandTotalling}.
     *
     * <p>Fewer than the 90 the service averages over, deliberately: the seeded totals are chosen
     * against that window, so the two numbers being different is part of what gets tested.
     */
    private static final int RECORDED_DAYS = 10;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryLevelRepository inventoryLevelRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private DemandHistoryRepository demandHistoryRepository;

    @Autowired
    private WithTestUsers users;

    private Warehouse east;
    private Warehouse west;

    /**
     * Four products across two warehouses, chosen so every cell state appears exactly once where
     * the assertions need it and the row totals stay checkable by hand.
     */
    @BeforeEach
    void seed() {
        inventoryLevelRepository.deleteAll();
        warehouseRepository.deleteAll();
        demandHistoryRepository.deleteAll();
        productRepository.deleteAll();
        users.reset();

        east = warehouse("WH-EAST", "East distribution centre", "East");
        west = warehouse("WH-WEST", "West distribution centre", "West");

        // 10/day over two locations is 5/day a cell. Lead time 7 puts the risk boundary at 14
        // days and the overstock boundary at 60, so 60 units in the east is trouble at 12 days
        // of cover while the same SKU reads 100 days across the network as a whole.
        product("WIDGET-1", "Widget", "Components", 7, 1000);
        demandTotalling("WIDGET-1", 900);
        level("WIDGET-1", east, 60, 0);     // 12 days  -> STOCKOUT_RISK
        level("WIDGET-1", west, 940, 0);    // 188 days -> OVERSTOCK

        // Balanced: both cells land between a 12-day risk boundary and the 60-day ceiling.
        product("STAPLE-2", "Staple", "Consumables", 5, 300);
        demandTotalling("STAPLE-2", 900);
        level("STAPLE-2", east, 150, 0);    // 30 days -> OK
        level("STAPLE-2", west, 150, 0);    // 30 days -> OK

        // Same on-hand either side; only the inbound order separates them.
        product("INBOUND-4", "Inbound part", "Components", 7, 40);
        demandTotalling("INBOUND-4", 900);
        level("INBOUND-4", east, 20, 200);  // 44 days -> OK
        level("INBOUND-4", west, 20, 0);    // 4 days  -> STOCKOUT_RISK

        // Catalogued, stocked, never sold: cover is unknown, which is not the same as healthy.
        product("SPARE-3", "Spare", "Spares", 30, 18);
        level("SPARE-3", east, 18, 0);
    }

    /** Columns come with the response so the grid has one header, not one per row. */
    @Test
    void returnsWarehouseColumnsInCodeOrderAlongsideTheRows() throws Exception {
        mockMvc.perform(get("/api/inventory/levels").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warehouses.length()").value(2))
                .andExpect(jsonPath("$.warehouses[0].code").value("WH-EAST"))
                .andExpect(jsonPath("$.warehouses[0].name").value("East distribution centre"))
                .andExpect(jsonPath("$.warehouses[0].region").value("East"))
                .andExpect(jsonPath("$.warehouses[1].code").value("WH-WEST"))
                .andExpect(jsonPath("$.rows.length()").value(4))
                .andExpect(jsonPath("$.rows[0].sku").value("INBOUND-4"))
                .andExpect(jsonPath("$.rows[1].sku").value("SPARE-3"))
                .andExpect(jsonPath("$.rows[2].sku").value("STAPLE-2"))
                .andExpect(jsonPath("$.rows[3].sku").value("WIDGET-1"))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    /**
     * Two cells in one row can differ, which is the entire premise of the page.
     *
     * <p>WIDGET-1 is the case it exists for: a hundred days of cover network-wide, and six days
     * short of its lead time in the east. Worth pinning as a regression test rather than only as
     * a feature test — allocating demand in proportion to on-hand instead would make every cell
     * in this row read 100 days, since the on-hand cancels out of the division.
     */
    @Test
    void cellsInOneRowCarryTheirOwnCoverAndStatus() throws Exception {
        mockMvc.perform(get("/api/inventory/levels").param("q", "WIDGET").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].dailyDemand").value(closeTo(10.0, 1e-6)))
                .andExpect(jsonPath("$.rows[0].totalOnHand").value(closeTo(1000.0, 1e-6)))
                .andExpect(jsonPath("$.rows[0].cells['WH-EAST'].onHand").value(closeTo(60.0, 1e-6)))
                .andExpect(jsonPath("$.rows[0].cells['WH-EAST'].daysOfSupply")
                        .value(closeTo(12.0, 1e-6)))
                .andExpect(jsonPath("$.rows[0].cells['WH-EAST'].status").value("STOCKOUT_RISK"))
                .andExpect(jsonPath("$.rows[0].cells['WH-WEST'].daysOfSupply")
                        .value(closeTo(188.0, 1e-6)))
                .andExpect(jsonPath("$.rows[0].cells['WH-WEST'].status").value("OVERSTOCK"));
    }

    /** Between the two boundaries is OK, measured against the product's own lead time. */
    @Test
    void coverBetweenTheThresholdsIsHealthy() throws Exception {
        mockMvc.perform(get("/api/inventory/levels").param("q", "STAPLE").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].cells['WH-EAST'].daysOfSupply")
                        .value(closeTo(30.0, 1e-6)))
                .andExpect(jsonPath("$.rows[0].cells['WH-EAST'].status").value("OK"))
                .andExpect(jsonPath("$.rows[0].cells['WH-WEST'].status").value("OK"));
    }

    /**
     * Cover counts what is inbound, not just what is on the shelf.
     *
     * <p>Both INBOUND-4 cells hold twenty units. Ignoring the two hundred on order would colour
     * them alike and send a planner to reorder stock already on its way.
     */
    @Test
    void inboundStockCountsTowardCover() throws Exception {
        mockMvc.perform(get("/api/inventory/levels").param("q", "INBOUND").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].totalOnOrder").value(closeTo(200.0, 1e-6)))
                .andExpect(jsonPath("$.rows[0].cells['WH-EAST'].onOrder").value(closeTo(200.0, 1e-6)))
                .andExpect(jsonPath("$.rows[0].cells['WH-EAST'].daysOfSupply")
                        .value(closeTo(44.0, 1e-6)))
                .andExpect(jsonPath("$.rows[0].cells['WH-EAST'].status").value("OK"))
                .andExpect(jsonPath("$.rows[0].cells['WH-WEST'].daysOfSupply")
                        .value(closeTo(4.0, 1e-6)))
                .andExpect(jsonPath("$.rows[0].cells['WH-WEST'].status").value("STOCKOUT_RISK"));
    }

    /**
     * The rate is averaged over the window, not over the days that recorded a sale.
     *
     * <p>WIDGET-1's 900 units arrive on ten days out of ninety. Averaging over the ten would call
     * it 90 a day and cut every cover figure on the row by nine tenths, turning a well-stocked
     * network into a page of red.
     */
    @Test
    void demandIsAveragedOverTheWholeWindowIncludingDaysWithNoSales() throws Exception {
        mockMvc.perform(get("/api/inventory/levels").param("q", "WIDGET").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].dailyDemand").value(closeTo(10.0, 1e-6)));
    }

    /**
     * A SKU with no demand history gets a null cover and a null status, not a healthy one.
     *
     * <p>Colouring an unmeasured cell green would be a claim the data does not support — the
     * page's legend carries a separate swatch for exactly this.
     */
    @Test
    void aSkuWithNoDemandHistoryHasUnknownCoverRatherThanHealthy() throws Exception {
        mockMvc.perform(get("/api/inventory/levels").param("q", "SPARE").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].dailyDemand").doesNotExist())
                .andExpect(jsonPath("$.rows[0].cells['WH-EAST'].onHand").value(closeTo(18.0, 1e-6)))
                .andExpect(jsonPath("$.rows[0].cells['WH-EAST'].daysOfSupply").doesNotExist())
                .andExpect(jsonPath("$.rows[0].cells['WH-EAST'].status").doesNotExist());
    }

    /**
     * A SKU that has been measured at zero demand is also unknown, but for a different reason.
     *
     * <p>Stock that sells nothing lasts indefinitely, and JSON has no way to write that, so the
     * cover is null here as well. The rate itself is present at zero rather than absent, which is
     * what separates this from a SKU nobody has recorded — the difference between "sells nothing"
     * and "we have never looked".
     */
    @Test
    void aSkuMeasuredAtZeroDemandReportsTheRateButNoCover() throws Exception {
        product("DORMANT-8", "Dormant part", "Spares", 7, 40);
        demandTotalling("DORMANT-8", 0);
        level("DORMANT-8", east, 40, 0);

        mockMvc.perform(get("/api/inventory/levels").param("q", "DORMANT").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].dailyDemand").value(closeTo(0.0, 1e-6)))
                .andExpect(jsonPath("$.rows[0].cells['WH-EAST'].daysOfSupply").doesNotExist())
                .andExpect(jsonPath("$.rows[0].cells['WH-EAST'].status").doesNotExist());
    }

    /**
     * A pair with no stock row is absent from {@code cells}, not a zero.
     *
     * <p>The page renders the two differently, and it should: nothing stocked here is a network
     * design decision, while zero on hand is a counted, empty shelf.
     */
    @Test
    void aProductNotStockedAtAWarehouseHasNoCellForIt() throws Exception {
        mockMvc.perform(get("/api/inventory/levels").param("q", "SPARE").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].cells['WH-EAST']").exists())
                .andExpect(jsonPath("$.rows[0].cells['WH-WEST']").doesNotExist());
    }

    /** Counts cover the whole filtered grid, so the legend does not shift as the user pages. */
    @Test
    void statusCountsCoverEveryCellIncludingTheUnmeasuredOnes() throws Exception {
        mockMvc.perform(get("/api/inventory/levels").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCounts.OK").value(3))
                .andExpect(jsonPath("$.statusCounts.STOCKOUT_RISK").value(2))
                .andExpect(jsonPath("$.statusCounts.OVERSTOCK").value(1))
                .andExpect(jsonPath("$.statusCounts.UNKNOWN").value(1));
    }

    /**
     * The status filter keeps products with at least one cell in that state.
     *
     * <p>Row-level rather than cell-level, because the page exists to find trouble and a row
     * stripped of its healthy cells would hide the context that makes the bad one readable.
     */
    @Test
    void theStatusFilterKeepsWholeRowsThatContainAMatchingCell() throws Exception {
        mockMvc.perform(get("/api/inventory/levels")
                        .param("status", "STOCKOUT_RISK")
                        .with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].sku").value("INBOUND-4"))
                .andExpect(jsonPath("$.rows[1].sku").value("WIDGET-1"))
                // WIDGET-1's overstocked cell comes too — the row is the unit, not the cell.
                .andExpect(jsonPath("$.rows[1].cells['WH-WEST'].status").value("OVERSTOCK"))
                .andExpect(jsonPath("$.totalMatching").value(2));
    }

    @Test
    void anUnknownStatusValueIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/inventory/levels")
                        .param("status", "SOMEWHAT_WORRYING")
                        .with(users.viewer()))
                .andExpect(status().isBadRequest());
    }

    /**
     * Narrowing to one warehouse narrows the columns and the cells, but not the arithmetic.
     *
     * <p>The east cell still reads twelve days, because demand is allocated over every location
     * the SKU is stocked at rather than over the ones currently on screen. If filtering changed
     * it, the same cell would tell two different stories depending on how the user got to it.
     */
    @Test
    void theWarehouseFilterNarrowsTheViewWithoutChangingTheAllocation() throws Exception {
        mockMvc.perform(get("/api/inventory/levels")
                        .param("warehouse", "wh-east")
                        .with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warehouses.length()").value(1))
                .andExpect(jsonPath("$.warehouses[0].code").value("WH-EAST"))
                .andExpect(jsonPath("$.rows[3].sku").value("WIDGET-1"))
                .andExpect(jsonPath("$.rows[3].cells['WH-WEST']").doesNotExist())
                .andExpect(jsonPath("$.rows[3].cells['WH-EAST'].daysOfSupply")
                        .value(closeTo(12.0, 1e-6)))
                // Summed over visible cells only, so a filtered grid is internally consistent.
                .andExpect(jsonPath("$.rows[3].totalOnHand").value(closeTo(60.0, 1e-6)))
                // The product aggregate is unfiltered by definition, which is why both are sent.
                .andExpect(jsonPath("$.rows[3].productStock").value(closeTo(1000.0, 1e-6)));
    }

    /** Filtering narrows the rows without narrowing the dropdown that would widen them again. */
    @Test
    void filtersByCategory() throws Exception {
        mockMvc.perform(get("/api/inventory/levels")
                        .param("category", "Components")
                        .with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].sku").value("INBOUND-4"))
                .andExpect(jsonPath("$.rows[1].sku").value("WIDGET-1"))
                .andExpect(jsonPath("$.categories.length()").value(3));
    }

    @Test
    void pagesTheRows() throws Exception {
        mockMvc.perform(get("/api/inventory/levels")
                        .param("page", "1").param("size", "3")
                        .with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].sku").value("WIDGET-1"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.totalMatching").value(4));
    }

    /** A page past the end is empty rather than an index error out of {@code subList}. */
    @Test
    void aPageBeyondTheLastOneIsEmpty() throws Exception {
        mockMvc.perform(get("/api/inventory/levels")
                        .param("page", "50")
                        .with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(0))
                .andExpect(jsonPath("$.totalMatching").value(4));
    }

    /** With no warehouses there are no columns, and so nothing to render rows against. */
    @Test
    void anEmptyWarehouseSetReturnsNoRowsRatherThanFailing() throws Exception {
        inventoryLevelRepository.deleteAll();
        warehouseRepository.deleteAll();

        mockMvc.perform(get("/api/inventory/levels").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warehouses.length()").value(0))
                .andExpect(jsonPath("$.rows.length()").value(0));
    }

    @Test
    void theGridRequiresAuthenticationAndIsReadableByEveryRole() throws Exception {
        mockMvc.perform(get("/api/inventory/levels"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/inventory/levels").with(users.viewer()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/inventory/levels").with(users.manager()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/inventory/levels").with(users.admin()))
                .andExpect(status().isOk());
    }

    /* Helpers -------------------------------------------------------------- */

    private Warehouse warehouse(String code, String name, String region) {
        return warehouseRepository.save(
                Warehouse.builder().code(code).name(name).region(region).build());
    }

    /**
     * @param currentStock the product-level aggregate the import would have recomputed, kept in
     *                     step with the seeded levels so the grid's divergence badge stays quiet
     */
    private void product(String sku, String name, String category, int leadTimeDays,
                         double currentStock) {
        productRepository.save(Product.builder()
                .sku(sku).name(name).category(category).leadTimeDays(leadTimeDays)
                .currentStock(currentStock)
                .build());
    }

    private void level(String sku, Warehouse warehouse, double onHand, double onOrder) {
        inventoryLevelRepository.save(InventoryLevel.builder()
                .sku(sku).warehouse(warehouse)
                .quantityOnHand(onHand).onOrder(onOrder)
                .build());
    }

    /**
     * Seeds history summing to {@code total} inside the service's 90-day window.
     *
     * <p>Spread over ten days rather than ninety, because the service divides by the window's
     * length and not by the number of rows — so ten rows are enough to fix the rate, and the gap
     * between the two counts is itself worth exercising.
     *
     * <p>The most recent row is dated yesterday. Today is excluded so a run near midnight cannot
     * land a row on a date the window has already moved past.
     */
    private void demandTotalling(String sku, double total) {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        double perDay = total / RECORDED_DAYS;
        for (int i = 0; i < RECORDED_DAYS; i++) {
            demandHistoryRepository.save(DemandHistory.builder()
                    .sku(sku).demandDate(yesterday.minusDays(i)).quantity(perDay).build());
        }
    }
}
