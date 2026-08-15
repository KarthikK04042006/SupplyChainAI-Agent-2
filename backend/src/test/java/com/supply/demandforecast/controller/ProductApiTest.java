package com.supply.demandforecast.controller;

import com.supply.demandforecast.entity.DemandHistory;
import com.supply.demandforecast.entity.Product;
import com.supply.demandforecast.repository.DemandHistoryRepository;
import com.supply.demandforecast.repository.ProductRepository;
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

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The catalogue read API: {@code GET /api/products}, one product, and its demand series.
 *
 * <p>No ML mock here, because none of these endpoints calls the ML service — which is itself worth
 * pinning. The product list is what three dashboard pages open with, and making it depend on a
 * forecast would mean an ML outage left the UI with nothing to render at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WithTestUsers.class)
class ProductApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private DemandHistoryRepository demandHistoryRepository;

    @Autowired
    private WithTestUsers users;

    @BeforeEach
    void seed() {
        demandHistoryRepository.deleteAll();
        productRepository.deleteAll();
        users.reset();

        // Priced, under its reorder point, nothing inbound.
        productRepository.save(Product.builder()
                .sku("ALPHA-1").name("Alpha widget").category("Components")
                .leadTimeDays(7).reorderPoint(100)
                .currentStock(40).onOrder(0).safetyStock(20.0).unitCost(12.50)
                .build());

        // Under its reorder point on the shelf, covered once inbound stock is counted.
        productRepository.save(Product.builder()
                .sku("BETA-2").name("Beta gadget").category("Finished goods")
                .leadTimeDays(14).reorderPoint(100)
                .currentStock(60).onOrder(80).unitCost(10.0)
                .build());

        // Unpriced and with no reorder point set — the "not measured" paths.
        productRepository.save(Product.builder()
                .sku("GAMMA-3").name("Gamma spare").category("Components")
                .leadTimeDays(21).currentStock(5).onOrder(0)
                .build());
    }

    /** Alphabetical by SKU, with the whole catalogue's categories alongside. */
    @Test
    void listsTheCatalogueSortedBySkuWithItsCategories() throws Exception {
        mockMvc.perform(get("/api/products").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(3))
                .andExpect(jsonPath("$.products[0].sku").value("ALPHA-1"))
                .andExpect(jsonPath("$.products[1].sku").value("BETA-2"))
                .andExpect(jsonPath("$.products[2].sku").value("GAMMA-3"))
                .andExpect(jsonPath("$.totalMatching").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.categories")
                        .value(contains("Components", "Finished goods")));
    }

    /** {@code q} spans SKU and name, so a planner does not have to know which holds the term. */
    @Test
    void searchMatchesSkuOrNameCaseInsensitively() throws Exception {
        mockMvc.perform(get("/api/products").param("q", "WIDGET").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(1))
                .andExpect(jsonPath("$.products[0].sku").value("ALPHA-1"));

        mockMvc.perform(get("/api/products").param("q", "beta-2").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(1))
                .andExpect(jsonPath("$.products[0].sku").value("BETA-2"));
    }

    @Test
    void filtersByCategory() throws Exception {
        mockMvc.perform(get("/api/products").param("category", "Components").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(2))
                // Narrowing the results must not narrow the filter's own options, or the page
                // loses the choice that would widen it again.
                .andExpect(jsonPath("$.categories.length()").value(2));
    }

    /**
     * A blank filter is an unset one.
     *
     * <p>{@code ?category=} is exactly what an unset dropdown sends, and treating it as a literal
     * empty category would return nothing on a page that had not been filtered.
     */
    @Test
    void blankFiltersAreTreatedAsAbsent() throws Exception {
        mockMvc.perform(get("/api/products")
                        .param("q", "  ").param("category", "")
                        .with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(3));
    }

    @Test
    void pagesTheCatalogue() throws Exception {
        mockMvc.perform(get("/api/products")
                        .param("page", "1").param("size", "2")
                        .with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(1))
                .andExpect(jsonPath("$.products[0].sku").value("GAMMA-3"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.totalMatching").value(3));
    }

    /** A negative page or a zero size is corrected rather than 500ing out of {@code PageRequest}. */
    @Test
    void nonsensicalPagingIsRejectedByValidation() throws Exception {
        mockMvc.perform(get("/api/products").param("page", "-1").with(users.viewer()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/products").param("size", "0").with(users.viewer()))
                .andExpect(status().isBadRequest());
    }

    /**
     * Stock value is null for an unpriced SKU, not zero.
     *
     * <p>The inventory-value KPI sums this figure. A zero here would let an unpriced catalogue
     * report a confident total that is quietly wrong, which is the distinction {@code
     * Product.unitCost} documents.
     */
    @Test
    void anUnpricedProductReportsNoStockValueRatherThanZero() throws Exception {
        mockMvc.perform(get("/api/products").param("q", "GAMMA").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].unitCost").doesNotExist())
                .andExpect(jsonPath("$.products[0].stockValue").doesNotExist())
                // No reorder point set is not "below" one.
                .andExpect(jsonPath("$.products[0].belowReorderPoint").value(false));
    }

    /**
     * The reorder flag reads inventory position, not what is on the shelf.
     *
     * <p>BETA-2 holds 60 against a reorder point of 100 but has 80 inbound. Flagging it would
     * prompt a planner to reorder something already ordered.
     */
    @Test
    void theReorderFlagCountsInboundStock() throws Exception {
        mockMvc.perform(get("/api/products").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].sku").value("ALPHA-1"))
                .andExpect(jsonPath("$.products[0].inventoryPosition").value(40.0))
                .andExpect(jsonPath("$.products[0].belowReorderPoint").value(true))
                .andExpect(jsonPath("$.products[0].stockValue").value(500.0))
                .andExpect(jsonPath("$.products[1].sku").value("BETA-2"))
                .andExpect(jsonPath("$.products[1].inventoryPosition").value(140.0))
                .andExpect(jsonPath("$.products[1].belowReorderPoint").value(false));
    }

    /** Every {@code /api/products/{id}} endpoint accepts either identifier. */
    @Test
    void oneProductResolvesByIdOrBySku() throws Exception {
        Long id = productRepository.findBySku("ALPHA-1").orElseThrow().getId();

        mockMvc.perform(get("/api/products/{id}", id).with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("ALPHA-1"));

        mockMvc.perform(get("/api/products/{id}", "ALPHA-1").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Alpha widget"))
                .andExpect(jsonPath("$.safetyStock").value(20.0));
    }

    @Test
    void anUnknownProductIsNotFound() throws Exception {
        mockMvc.perform(get("/api/products/{id}", "NO-SUCH-SKU").with(users.viewer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Product not found"));
    }

    /** Oldest first, which is the order a chart draws in. */
    @Test
    void demandHistoryComesBackOldestFirstWithItsObservationCount() throws Exception {
        history("ALPHA-1", 5, 12.0);

        mockMvc.perform(get("/api/products/{id}/demand-history", "ALPHA-1").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("ALPHA-1"))
                .andExpect(jsonPath("$.points.length()").value(5))
                .andExpect(jsonPath("$.points[0].date").value("2026-01-01"))
                .andExpect(jsonPath("$.points[4].date").value("2026-01-05"))
                .andExpect(jsonPath("$.points[0].quantity").value(12.0))
                .andExpect(jsonPath("$.observations").value(5))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    /**
     * A limit keeps the most recent days and says it truncated.
     *
     * <p>Dropping the oldest is what a chart wants; reporting the drop is what stops the page
     * from implying the SKU has no earlier history.
     */
    @Test
    void aLimitKeepsTheMostRecentDaysAndFlagsTheTruncation() throws Exception {
        history("ALPHA-1", 10, 4.0);

        mockMvc.perform(get("/api/products/{id}/demand-history", "ALPHA-1")
                        .param("limit", "3")
                        .with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(3))
                .andExpect(jsonPath("$.points[0].date").value("2026-01-08"))
                .andExpect(jsonPath("$.points[2].date").value("2026-01-10"))
                .andExpect(jsonPath("$.observations").value(10))
                .andExpect(jsonPath("$.truncated").value(true));
    }

    /** A product with nothing recorded is an empty series, not a 404. */
    @Test
    void aProductWithNoHistoryReturnsAnEmptySeries() throws Exception {
        mockMvc.perform(get("/api/products/{id}/demand-history", "GAMMA-3").with(users.viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(0))
                .andExpect(jsonPath("$.observations").value(0));
    }

    /** The catalogue is readable by every role, and by nobody without a token. */
    @Test
    void theCatalogueRequiresAuthenticationButOnlyTheViewerRole() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/products/{id}", "ALPHA-1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/products/{id}/demand-history", "ALPHA-1"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/products").with(users.viewer()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/products").with(users.manager()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/products").with(users.admin()))
                .andExpect(status().isOk());
    }

    private void history(String sku, int days, double quantity) {
        LocalDate start = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < days; i++) {
            demandHistoryRepository.save(DemandHistory.builder()
                    .sku(sku).demandDate(start.plusDays(i)).quantity(quantity).build());
        }
    }
}
