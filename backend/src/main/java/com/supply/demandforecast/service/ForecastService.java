package com.supply.demandforecast.service;

import com.supply.demandforecast.client.MlServiceClient;
import com.supply.demandforecast.dto.ForecastRequest;
import com.supply.demandforecast.dto.ForecastResponse;
import com.supply.demandforecast.dto.MlForecastRequest;
import com.supply.demandforecast.dto.ProductForecastResponse;
import com.supply.demandforecast.entity.DemandHistory;
import com.supply.demandforecast.entity.Product;
import com.supply.demandforecast.repository.DemandHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Orchestrates forecasting: loads a SKU's history from the database, hands it to the
 * stateless ML service, and returns the prediction.
 *
 * <p>This is the seam the architecture depends on — the ML service never reads the
 * database, so history has to travel through here on every call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForecastService {

    /** Below this many observations, smoothing has nothing useful to work with. */
    private static final int MIN_HISTORY_POINTS = 2;

    private final DemandHistoryRepository demandHistoryRepository;
    private final MlServiceClient mlServiceClient;
    private final ProductLookup productLookup;

    @Transactional(readOnly = true)
    public ForecastResponse forecast(String sku, ForecastRequest request) {
        List<DemandHistory> history = demandHistoryRepository.findBySkuOrderByDemandDateAsc(sku);

        if (history.size() < MIN_HISTORY_POINTS) {
            throw new InsufficientHistoryException(sku, history.size(), MIN_HISTORY_POINTS);
        }

        MlForecastRequest mlRequest = new MlForecastRequest(
                sku,
                request.horizonDaysOrDefault(),
                request.seasonalPeriodsOrDefault(),
                true,
                null,
                history.stream()
                        .map(point -> new MlForecastRequest.DemandPoint(
                                point.getDemandDate(), point.getQuantity()))
                        .toList()
        );

        log.debug("Forecasting {} from {} observations over {} days",
                sku, history.size(), mlRequest.horizonDays());

        return mlServiceClient.forecast(mlRequest);
    }

    /**
     * Forecasts a product identified by id or SKU, returning stock position and history
     * extent alongside the prediction.
     *
     * <p>The path variable may be either form; {@link ProductLookup} owns what that means, so
     * every endpoint under {@code /api/products/{id}} resolves it the same way.
     *
     * @throws ProductNotFoundException      if neither lookup resolves
     * @throws InsufficientHistoryException  if the product has too little recorded demand
     */
    @Transactional(readOnly = true)
    public ProductForecastResponse forecastForProduct(String idOrSku, ForecastRequest request) {
        Product product = productLookup.resolve(idOrSku);
        List<DemandHistory> history =
                demandHistoryRepository.findBySkuOrderByDemandDateAsc(product.getSku());

        if (history.size() < MIN_HISTORY_POINTS) {
            throw new InsufficientHistoryException(
                    product.getSku(), history.size(), MIN_HISTORY_POINTS);
        }

        ForecastResponse forecast = forecast(product.getSku(), request);

        return new ProductForecastResponse(
                ProductForecastResponse.ProductSummary.from(product),
                ProductForecastResponse.HistorySummary.of(
                        history.stream().map(DemandHistory::getDemandDate).toList(),
                        history.stream().map(DemandHistory::getQuantity).toList()),
                forecast);
    }
}
