package com.supply.demandforecast.controller;

import com.supply.demandforecast.entity.InventoryLevel;
import com.supply.demandforecast.entity.Product;
import com.supply.demandforecast.entity.Warehouse;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Both new CSV endpoints over HTTP: the catalogue and per-warehouse stock.
 *
 * <p>Driven through the endpoint rather than the service because two of the behaviours that matter
 * are not in the parsing at all — the ADMIN gate, and the product-total recompute the inventory
 * import performs across real repositories in one transaction.
 *
 * <p>The distinction the upload page renders is asserted throughout: a file with bad rows is a 200
 * carrying those rows, and only an unreadable or empty file is a 400. Getting that backwards would
 * tell an operator nothing loaded when nine rows in ten did.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WithTestUsers.class)
class CsvImportApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryLevelRepository inventoryLevelRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private WithTestUsers users;

    @BeforeEach
    void seed() {
        inventoryLevelRepository.deleteAll();
        warehouseRepository.deleteAll();
        productRepository.deleteAll();
        users.reset();
    }

    /* Catalogue import ----------------------------------------------------- */

    @Test
    void importsACatalogueAndReportsWhatItDid() throws Exception {
        products("""
                sku,name,category,lead_time_days,reorder_point,safety_stock,unit_cost
                WIDGET-001,"Widget, standard 10mm",Components,7,400,150,12.50
                GADGET-002,Gadget Pro,Finished goods,14,900,320,48.00
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsParsed").value(2))
                .andExpect(jsonPath("$.inserted").value(2))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.rejected").value(0))
                .andExpect(jsonPath("$.errors.length()").value(0));

        Product widget = productRepository.findBySku("WIDGET-001").orElseThrow();
        // The quoted name keeps its comma, which is the whole reason for a CSV library.
        assertThat(widget.getName()).isEqualTo("Widget, standard 10mm");
        assertThat(widget.getLeadTimeDays()).isEqualTo(7);
        assertThat(widget.getReorderPoint()).isEqualTo(400);
        assertThat(widget.getSafetyStock()).isEqualTo(150.0);
        assertThat(widget.getUnitCost()).isEqualTo(12.50);
    }

    @Test
    void columnOrderDoesNotMatterAndOptionalColumnsMayBeAbsent() throws Exception {
        products("""
                name,sku
                Sensor module v2,NEWITEM-005
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(1));

        Product product = productRepository.findBySku("NEWITEM-005").orElseThrow();
        assertThat(product.getUnitCost()).isNull();
        assertThat(product.getReorderPoint()).isNull();
    }

    /**
     * Re-uploading updates the rows it restates, and touches nothing it does not mention.
     *
     * <p>This is what makes a corrected file safe to send again — the behaviour the upload page
     * promises in its header text. The stock assertion is the important half: a catalogue file
     * listing lead times must not zero the levels an inventory upload established.
     */
    @Test
    void reuploadingUpdatesNamedColumnsAndLeavesAbsentOnesAlone() throws Exception {
        productRepository.save(Product.builder()
                .sku("WIDGET-001").name("Old name").category("Components")
                .leadTimeDays(7).reorderPoint(400)
                .currentStock(180).onOrder(50).unitCost(12.50)
                .build());

        products("""
                sku,name,lead_time_days
                WIDGET-001,Widget standard 10mm,9
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(0))
                .andExpect(jsonPath("$.updated").value(1));

        Product widget = productRepository.findBySku("WIDGET-001").orElseThrow();
        assertThat(widget.getName()).isEqualTo("Widget standard 10mm");
        assertThat(widget.getLeadTimeDays()).isEqualTo(9);
        assertThat(widget.getCurrentStock()).isEqualTo(180.0);
        assertThat(widget.getOnOrder()).isEqualTo(50.0);
        assertThat(widget.getUnitCost()).isEqualTo(12.50);
        assertThat(widget.getReorderPoint()).isEqualTo(400);
    }

    /**
     * Bad rows are reported with their line numbers; the rest load.
     *
     * <p>The line number is the value of the error list — "row 4" is actionable in a spreadsheet,
     * "invalid number" is not.
     */
    @Test
    void rejectsBadCatalogueRowsWithLineNumbersAndKeepsTheGoodOnes() throws Exception {
        products("""
                sku,name,lead_time_days,unit_cost
                GOOD-1,First good,7,10.00
                ,Missing its sku,7,10.00
                BAD-3,Fractional lead time,7.5,10.00
                BAD-4,Unit cost is words,7,not-a-number
                GOOD-5,Second good,7,10.00
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsParsed").value(2))
                .andExpect(jsonPath("$.inserted").value(2))
                .andExpect(jsonPath("$.rejected").value(3))
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[0]").value(containsString("Row 3")))
                .andExpect(jsonPath("$.errors[0]").value(containsString("sku")))
                .andExpect(jsonPath("$.errors[1]").value(containsString("Row 4")))
                .andExpect(jsonPath("$.errors[1]").value(containsString("whole number")))
                .andExpect(jsonPath("$.errors[2]").value(containsString("Row 5")))
                .andExpect(jsonPath("$.errors[2]").value(containsString("not a number")));

        assertThat(productRepository.findAll())
                .extracting(Product::getSku)
                .containsExactlyInAnyOrder("GOOD-1", "GOOD-5");
    }

    /** A file listing the same SKU twice collapses to its last row rather than racing itself. */
    @Test
    void aDuplicateSkuWithinOneFileCollapsesToItsLastRow() throws Exception {
        products("""
                sku,name,lead_time_days
                WIDGET-001,First spelling,7
                WIDGET-001,Second spelling,11
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsParsed").value(2))
                .andExpect(jsonPath("$.inserted").value(1));

        Product widget = productRepository.findBySku("WIDGET-001").orElseThrow();
        assertThat(widget.getName()).isEqualTo("Second spelling");
        assertThat(widget.getLeadTimeDays()).isEqualTo(11);
    }

    /** An empty upload is the one catalogue failure that is a 400: nothing could be loaded. */
    @Test
    void anEmptyCatalogueUploadIsRejectedWhole() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/products/import")
                        .file(csvFile("products.csv", ""))
                        .with(users.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed CSV upload"))
                .andExpect(jsonPath("$.detail").value(containsString("empty")));
    }

    /* Inventory import ----------------------------------------------------- */

    /** Warehouses are created from the file, so there is no separate registration step to run. */
    @Test
    void importsStockAndCreatesTheWarehousesTheFileNames() throws Exception {
        catalogue("WIDGET-001", 7);

        inventory("""
                sku,warehouse,warehouse_name,region,quantity_on_hand,on_order
                WIDGET-001,WH-EAST,East distribution centre,East,180,0
                WIDGET-001,WH-WEST,West distribution centre,West,150,400
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsParsed").value(2))
                .andExpect(jsonPath("$.inserted").value(2))
                .andExpect(jsonPath("$.rejected").value(0));

        assertThat(warehouseRepository.findByActiveTrueOrderByCodeAsc())
                .extracting(Warehouse::getCode, Warehouse::getName, Warehouse::getRegion)
                .containsExactly(
                        tuple("WH-EAST", "East distribution centre", "East"),
                        tuple("WH-WEST", "West distribution centre", "West"));
    }

    /**
     * The product aggregate is restated from the imported rows.
     *
     * <p>{@code Product.currentStock} is what the risk scan and the KPI cards read. Leaving it
     * stale would let the heatmap and the alert list describe different inventories, which is the
     * one divergence the grid has a badge for.
     */
    @Test
    void recomputesTheProductTotalsTheScanReads() throws Exception {
        catalogue("WIDGET-001", 7);

        inventory("""
                sku,warehouse,quantity_on_hand,on_order
                WIDGET-001,WH-EAST,180,0
                WIDGET-001,WH-WEST,150,400
                WIDGET-001,WH-CENTRAL,70,0
                """)
                .andExpect(status().isOk());

        Product widget = productRepository.findBySku("WIDGET-001").orElseThrow();
        assertThat(widget.getCurrentStock()).isEqualTo(400.0);
        assertThat(widget.getOnOrder()).isEqualTo(400.0);
    }

    /** A second upload restates a cell rather than adding one, and the total follows it down. */
    @Test
    void reimportingACellUpdatesItRatherThanDuplicatingIt() throws Exception {
        catalogue("WIDGET-001", 7);

        inventory("""
                sku,warehouse,quantity_on_hand,on_order
                WIDGET-001,WH-EAST,180,0
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(1));

        inventory("""
                sku,warehouse,quantity_on_hand,on_order
                WIDGET-001,WH-EAST,25,10
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(0))
                .andExpect(jsonPath("$.updated").value(1));

        assertThat(inventoryLevelRepository.findBySku("WIDGET-001"))
                .singleElement()
                .extracting(InventoryLevel::getQuantityOnHand, InventoryLevel::getOnOrder)
                .containsExactly(25.0, 10.0);
        assertThat(productRepository.findBySku("WIDGET-001").orElseThrow().getCurrentStock())
                .isEqualTo(25.0);
    }

    /**
     * Stock for an uncatalogued SKU is stored, not invented as a product.
     *
     * <p>A product fabricated from a stock file would carry a zero-day lead time, which quietly
     * distorts every forecast it appears in. The level is kept so the row starts working the
     * moment the catalogue lands — which is why the upload page lists the catalogue first rather
     * than refusing this file.
     */
    @Test
    void stockForAnUnknownSkuIsKeptWithoutInventingAProduct() throws Exception {
        inventory("""
                sku,warehouse,quantity_on_hand
                ORPHAN-9,WH-EAST,40
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(1))
                .andExpect(jsonPath("$.rejected").value(0));

        assertThat(inventoryLevelRepository.findBySku("ORPHAN-9")).hasSize(1);
        assertThat(productRepository.findBySku("ORPHAN-9")).isEmpty();
    }

    /** An omitted optional column must not blank out what an earlier upload established. */
    @Test
    void aLaterFileWithoutTheOptionalColumnsKeepsTheWarehouseDetails() throws Exception {
        inventory("""
                sku,warehouse,warehouse_name,region,quantity_on_hand
                WIDGET-001,WH-EAST,East distribution centre,East,180
                """)
                .andExpect(status().isOk());

        inventory("""
                sku,warehouse,quantity_on_hand
                WIDGET-001,WH-EAST,190
                """)
                .andExpect(status().isOk());

        Warehouse east = warehouseRepository.findByCode("WH-EAST").orElseThrow();
        assertThat(east.getName()).isEqualTo("East distribution centre");
        assertThat(east.getRegion()).isEqualTo("East");
    }

    @Test
    void rejectsBadStockRowsWithLineNumbersAndKeepsTheGoodOnes() throws Exception {
        inventory("""
                sku,warehouse,quantity_on_hand,on_order
                WIDGET-001,WH-EAST,180,0
                WIDGET-001,,50,0
                WIDGET-001,WH-WEST,,0
                WIDGET-001,WH-CENTRAL,-5,0
                WIDGET-001,WH-NORTH,60,0
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsParsed").value(2))
                .andExpect(jsonPath("$.rejected").value(3))
                .andExpect(jsonPath("$.errors[0]").value(containsString("Row 3")))
                .andExpect(jsonPath("$.errors[0]").value(containsString("warehouse")))
                .andExpect(jsonPath("$.errors[1]").value(containsString("Row 4")))
                .andExpect(jsonPath("$.errors[2]").value(containsString("Row 5")))
                .andExpect(jsonPath("$.errors[2]").value(containsString("negative")));

        // A rejected row creates no warehouse either: WH-CENTRAL is only named by the -5 line.
        assertThat(warehouseRepository.findByActiveTrueOrderByCodeAsc())
                .extracting(Warehouse::getCode)
                .containsExactly("WH-EAST", "WH-NORTH");
    }

    @Test
    void anEmptyStockUploadIsRejectedWhole() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/inventory/import")
                        .file(csvFile("inventory.csv", ""))
                        .with(users.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed CSV upload"));
    }

    /* Role gating ---------------------------------------------------------- */

    /**
     * Both imports are ADMIN, and MANAGER is refused as firmly as VIEWER.
     *
     * <p>Worth asserting the MANAGER case explicitly: the role hierarchy makes MANAGER cover the
     * scan and the resolve endpoints, so "a manager can do the operational things" is a plausible
     * reading. Replacing the catalogue is not one of them.
     */
    @Test
    void onlyAdminCanImport() throws Exception {
        for (String path : new String[] {"/api/products/import", "/api/inventory/import"}) {
            mockMvc.perform(MockMvcRequestBuilders.multipart(path)
                            .file(csvFile("f.csv", "sku,name\nX-1,X\n")))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(MockMvcRequestBuilders.multipart(path)
                            .file(csvFile("f.csv", "sku,name\nX-1,X\n"))
                            .with(users.viewer()))
                    .andExpect(status().isForbidden());

            mockMvc.perform(MockMvcRequestBuilders.multipart(path)
                            .file(csvFile("f.csv", "sku,name\nX-1,X\n"))
                            .with(users.manager()))
                    .andExpect(status().isForbidden());
        }

        assertThat(productRepository.findAll()).isEmpty();
        assertThat(inventoryLevelRepository.findAll()).isEmpty();
    }

    /* Helpers -------------------------------------------------------------- */

    private ResultActions products(String csv) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.multipart("/api/products/import")
                .file(csvFile("products.csv", csv))
                .with(users.admin()));
    }

    private ResultActions inventory(String csv) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.multipart("/api/inventory/import")
                .file(csvFile("inventory_levels.csv", csv))
                .with(users.admin()));
    }

    private void catalogue(String sku, int leadTimeDays) {
        productRepository.save(Product.builder()
                .sku(sku).name(sku + " product").leadTimeDays(leadTimeDays).build());
    }

    private static MockMultipartFile csvFile(String name, String csv) {
        return new MockMultipartFile(
                "file", name, "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }
}
