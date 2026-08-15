package com.supply.demandforecast.service;

import com.supply.demandforecast.client.MlServiceClient;
import com.supply.demandforecast.dto.ExplainRequest;
import com.supply.demandforecast.dto.ExplainResponse;
import com.supply.demandforecast.dto.ForecastRequest;
import com.supply.demandforecast.dto.ForecastResponse;
import com.supply.demandforecast.dto.MlForecastRequest;
import com.supply.demandforecast.dto.ReorderRecommendationResponse;
import com.supply.demandforecast.dto.ReorderRequest;
import com.supply.demandforecast.entity.DemandHistory;
import com.supply.demandforecast.entity.Product;
import com.supply.demandforecast.repository.DemandHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The two per-product ML calls the product page needs beyond the forecast: what to reorder, and
 * why the model predicted what it did.
 *
 * <p>Both are separate endpoints rather than extra fields on the forecast response. The product
 * page fetches them independently so a SHAP failure — which is routine, since only the tree model
 * supports it — still leaves the chart and the reorder panel on screen.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplenishmentService {

    /** Matches {@link ForecastService}: below this, there is nothing to extrapolate from. */
    private static final int MIN_HISTORY_POINTS = 2;

    /** Ordering cost per purchase order, in the same currency as {@code unitCost}. */
    private static final double ORDERING_COST = 50.0;

    /** Annual holding cost as a fraction of unit cost — storage, capital, and shrinkage. */
    private static final double HOLDING_COST_RATE = 0.25;

    private final DemandHistoryRepository demandHistoryRepository;
    private final MlServiceClient mlServiceClient;
    private final ProductLookup productLookup;
    private final ForecastService forecastService;
    private final InventoryScanProperties scanProperties;

    /**
     * Safety stock, reorder point and order quantity for one product.
     *
     * <p>The forecast is fetched first and passed as {@code forecastDailyDemand}, because a
     * recommendation built on a flat historical average would ignore the trend the forecast
     * exists to capture. The history goes along with it so the ML service can derive demand
     * variability — safety stock is a function of that variability, not of the mean.
     *
     * <p>Costs are supplied only when the product is priced, which is what selects EOQ over the
     * service's coverage heuristic. An unpriced product gets the heuristic and says so in {@code
     * methodNote} rather than being valued at a made-up unit cost.
     */
    @Transactional(readOnly = true)
    public ReorderRecommendationResponse recommend(String idOrSku) {
        Product product = productLookup.resolve(idOrSku);
        List<DemandHistory> history =
                demandHistoryRepository.findBySkuOrderByDemandDateAsc(product.getSku());

        if (history.size() < MIN_HISTORY_POINTS) {
            throw new InsufficientHistoryException(
                    product.getSku(), history.size(), MIN_HISTORY_POINTS);
        }

        ForecastResponse forecast = forecastService.forecast(
                product.getSku(), new ForecastRequest(scanProperties.horizonDays(), null));

        double leadTime = product.getLeadTimeDays() > 0
                ? product.getLeadTimeDays()
                : scanProperties.defaultLeadTimeDays();

        return mlServiceClient.reorderRecommendation(new ReorderRequest(
                product.getSku(),
                product.getCurrentStock(),
                product.getOnOrder(),
                leadTime,
                null,
                null,
                forecast.points().stream()
                        .map(ForecastResponse.ForecastPoint::predicted)
                        .toList(),
                null,
                demandPoints(history),
                scanProperties.reviewPeriodDays(),
                costs(product),
                null,
                null,
                null));
    }

    /**
     * Per-feature SHAP contributions for the first forecast day.
     *
     * <p>Propagates the ML service's rejection rather than translating it into an empty response:
     * the service answers 422 when the selected model has no SHAP support, and the difference
     * between "this model cannot be explained" and "nothing influenced the prediction" is the
     * whole content of the panel.
     */
    @Transactional(readOnly = true)
    public ExplainResponse explain(String idOrSku) {
        Product product = productLookup.resolve(idOrSku);
        List<DemandHistory> history =
                demandHistoryRepository.findBySkuOrderByDemandDateAsc(product.getSku());

        if (history.size() < MIN_HISTORY_POINTS) {
            throw new InsufficientHistoryException(
                    product.getSku(), history.size(), MIN_HISTORY_POINTS);
        }

        return mlServiceClient.explain(product.getSku(),
                ExplainRequest.of(scanProperties.horizonDays(), demandPoints(history)));
    }

    /**
     * Costs for an EOQ calculation, or null when the product has no unit cost.
     *
     * <p>Holding cost is expressed as a rate on unit cost rather than as an absolute, so one
     * constant covers a catalogue whose items differ in value by orders of magnitude.
     */
    private static ReorderRequest.Costs costs(Product product) {
        if (product.getUnitCost() == null) {
            return null;
        }
        return new ReorderRequest.Costs(
                ORDERING_COST, null, product.getUnitCost(), HOLDING_COST_RATE);
    }

    private static List<MlForecastRequest.DemandPoint> demandPoints(List<DemandHistory> history) {
        return history.stream()
                .map(point -> new MlForecastRequest.DemandPoint(
                        point.getDemandDate(), point.getQuantity()))
                .toList();
    }
}
