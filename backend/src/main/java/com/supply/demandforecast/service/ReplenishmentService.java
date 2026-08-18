package com.supply.demandforecast.service;

import com.supply.demandforecast.client.MlServiceClient;
import com.supply.demandforecast.dto.ExplainRequest;
import com.supply.demandforecast.dto.ExplainResponse;
import com.supply.demandforecast.dto.ForecastRequest;
import com.supply.demandforecast.dto.ForecastResponse;
import com.supply.demandforecast.dto.MlForecastRequest;
import com.supply.demandforecast.dto.ReorderRecommendationResponse;
import com.supply.demandforecast.dto.ReorderRequest;
import com.supply.demandforecast.dto.SimulateRequest;
import com.supply.demandforecast.dto.SimulationResponse;
import com.supply.demandforecast.entity.DemandHistory;
import com.supply.demandforecast.entity.Product;
import com.supply.demandforecast.repository.DemandHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The per-product ML calls the product page needs beyond the forecast: what to reorder, the
 * same recomputed under a hypothetical shock, and why the model predicted what it did.
 *
 * <p>The reorder and explain calls are separate endpoints rather than extra fields on the
 * forecast response. The product page fetches them independently so a SHAP failure — which is
 * routine, since only the tree model supports it — still leaves the chart and the reorder panel
 * on screen. The what-if simulation reuses the reorder path wholesale (see {@link #simulate}),
 * perturbing the forecast in memory rather than running a parallel calculation.
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

    /** The ML service caps a forecast horizon at 365 days; a widened simulation obeys the same. */
    private static final int MAX_HORIZON_DAYS = 365;

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
        List<DemandHistory> history = loadHistory(product);

        ForecastResponse forecast = forecastService.forecast(
                product.getSku(), new ForecastRequest(scanProperties.horizonDays(), null));

        List<Double> forecastDailyDemand = forecast.points().stream()
                .map(ForecastResponse.ForecastPoint::predicted)
                .toList();

        return recommendFrom(product, history, forecastDailyDemand, leadTimeFor(product));
    }

    /**
     * Recomputes the reorder recommendation under a hypothetical shock, persisting nothing.
     *
     * <p>A single forecast is fetched and perturbed in memory, then fed through the same ML
     * recommendation call as {@link #recommend} — once unshocked, once shocked — so the two
     * results differ only by the shock. Sharing one forecast is what keeps them comparable and
     * avoids a second, slower model run.
     *
     * <p>Two knobs, per {@link SimulateRequest}: a demand multiplier over a date window, and a
     * lead-time override. The window is matched against the forecast's own dates, and the
     * horizon is widened (see {@link #horizonFor}) so a promotion weeks out still lands inside
     * the forecast rather than past its end.
     *
     * <p><b>Safety stock does not move under a demand multiplier.</b> The ML service derives it
     * from the variability of the supplied <em>history</em>, which the shock leaves untouched;
     * only the mean shifts, so order quantity and reorder point respond while the buffer holds.
     * A lead-time override does move it, because the protection window widens. The what-if panel
     * says as much.
     */
    @Transactional(readOnly = true)
    public SimulationResponse simulate(SimulateRequest request) {
        Product product = productLookup.resolve(request.sku());
        List<DemandHistory> history = loadHistory(product);

        int horizon = horizonFor(request, history);
        ForecastResponse forecast = forecastService.forecast(
                product.getSku(), new ForecastRequest(horizon, null));

        List<Double> baselineDemand = forecast.points().stream()
                .map(ForecastResponse.ForecastPoint::predicted)
                .toList();
        List<Double> shockedDemand = applyDemandShock(forecast, request);

        double baselineLeadTime = leadTimeFor(product);
        double shockedLeadTime = request.leadTimeDaysOverride() != null
                ? request.leadTimeDaysOverride()
                : baselineLeadTime;

        ReorderRecommendationResponse baseline =
                recommendFrom(product, history, baselineDemand, baselineLeadTime);
        ReorderRecommendationResponse simulated =
                recommendFrom(product, history, shockedDemand, shockedLeadTime);

        return new SimulationResponse(product.getSku(), baseline, simulated);
    }

    /**
     * Builds and sends one reorder request. Every recommendation — baseline, shocked, or plain —
     * funnels through here, so they cannot drift apart in how the request is assembled.
     */
    private ReorderRecommendationResponse recommendFrom(
            Product product, List<DemandHistory> history,
            List<Double> forecastDailyDemand, double leadTime) {
        return mlServiceClient.reorderRecommendation(new ReorderRequest(
                product.getSku(),
                product.getCurrentStock(),
                product.getOnOrder(),
                leadTime,
                null,
                null,
                forecastDailyDemand,
                null,
                demandPoints(history),
                scanProperties.reviewPeriodDays(),
                costs(product),
                null,
                null,
                null));
    }

    /** Loads a product's history, refusing anything too short to extrapolate from. */
    private List<DemandHistory> loadHistory(Product product) {
        List<DemandHistory> history =
                demandHistoryRepository.findBySkuOrderByDemandDateAsc(product.getSku());
        if (history.size() < MIN_HISTORY_POINTS) {
            throw new InsufficientHistoryException(
                    product.getSku(), history.size(), MIN_HISTORY_POINTS);
        }
        return history;
    }

    /** A product's own lead time, or the batch default when it has none. */
    private double leadTimeFor(Product product) {
        return product.getLeadTimeDays() > 0
                ? product.getLeadTimeDays()
                : scanProperties.defaultLeadTimeDays();
    }

    /**
     * The forecast horizon for a simulation: the standard scan horizon, widened when a demand
     * window sits beyond it so the window's days fall inside the returned series.
     *
     * <p>Forecast days begin the day after the last recorded demand — the ML service dates them
     * from the history's end — so the window is measured from there, not from today. Clamped to
     * the ML service's own 1–365 range.
     */
    private int horizonFor(SimulateRequest request, List<DemandHistory> history) {
        int base = scanProperties.horizonDays();
        if (!request.hasDemandShock()) {
            return base;
        }
        LocalDate forecastStart =
                history.get(history.size() - 1).getDemandDate().plusDays(1);
        long needed = ChronoUnit.DAYS.between(forecastStart, request.windowStartDate())
                + request.windowDays();
        long horizon = Math.min(MAX_HORIZON_DAYS, Math.max(base, needed));
        return (int) Math.max(1, horizon);
    }

    /**
     * The forecast's daily demand with the multiplier applied to the days inside the window.
     *
     * <p>Matching is by calendar date against the forecast points themselves. A window falling
     * entirely outside the returned days multiplies nothing, and the simulation collapses to the
     * baseline for demand — an honest "this promotion is outside the forecastable window" rather
     * than a silent error.
     */
    private static List<Double> applyDemandShock(ForecastResponse forecast, SimulateRequest request) {
        if (!request.hasDemandShock()) {
            return forecast.points().stream()
                    .map(ForecastResponse.ForecastPoint::predicted)
                    .toList();
        }
        LocalDate start = request.windowStartDate();
        LocalDate endExclusive = start.plusDays(request.windowDays());
        double multiplier = request.demandMultiplier();

        return forecast.points().stream()
                .map(point -> {
                    LocalDate date = point.date();
                    boolean inWindow = date != null
                            && !date.isBefore(start)
                            && date.isBefore(endExclusive);
                    return inWindow ? point.predicted() * multiplier : point.predicted();
                })
                .toList();
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
