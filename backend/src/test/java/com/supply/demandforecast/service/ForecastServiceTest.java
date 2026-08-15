package com.supply.demandforecast.service;

import com.supply.demandforecast.client.MlServiceClient;
import com.supply.demandforecast.dto.ForecastRequest;
import com.supply.demandforecast.dto.ForecastResponse;
import com.supply.demandforecast.dto.MlForecastRequest;
import com.supply.demandforecast.entity.DemandHistory;
import com.supply.demandforecast.repository.DemandHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ForecastServiceTest {

    private static final String SKU = "WIDGET-001";

    @Mock
    private DemandHistoryRepository demandHistoryRepository;

    @Mock
    private MlServiceClient mlServiceClient;

    @InjectMocks
    private ForecastService forecastService;

    @Test
    void passesStoredHistoryToMlServiceAndReturnsItsForecast() {
        when(demandHistoryRepository.findBySkuOrderByDemandDateAsc(SKU))
                .thenReturn(List.of(
                        history(LocalDate.of(2026, 7, 1), 10),
                        history(LocalDate.of(2026, 7, 2), 12),
                        history(LocalDate.of(2026, 7, 3), 11)));

        ForecastResponse expected = forecastResponse("holt-linear-trend", 3);
        when(mlServiceClient.forecast(any())).thenReturn(expected);

        ForecastResponse actual = forecastService.forecast(SKU, new ForecastRequest(5, 7));

        assertThat(actual).isSameAs(expected);

        ArgumentCaptor<MlForecastRequest> captor = ArgumentCaptor.forClass(MlForecastRequest.class);
        verify(mlServiceClient).forecast(captor.capture());
        MlForecastRequest sent = captor.getValue();

        assertThat(sent.sku()).isEqualTo(SKU);
        assertThat(sent.horizonDays()).isEqualTo(5);
        assertThat(sent.seasonalPeriods()).isEqualTo(7);
        assertThat(sent.history()).hasSize(3);
        assertThat(sent.history().getFirst().date()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(sent.history().getFirst().quantity()).isEqualTo(10);
    }

    @Test
    void appliesDefaultsWhenRequestOmitsThem() {
        when(demandHistoryRepository.findBySkuOrderByDemandDateAsc(SKU))
                .thenReturn(List.of(
                        history(LocalDate.of(2026, 7, 1), 10),
                        history(LocalDate.of(2026, 7, 2), 12)));
        when(mlServiceClient.forecast(any()))
                .thenReturn(forecastResponse("naive-last-value", 2));

        forecastService.forecast(SKU, new ForecastRequest(null, null));

        ArgumentCaptor<MlForecastRequest> captor = ArgumentCaptor.forClass(MlForecastRequest.class);
        verify(mlServiceClient).forecast(captor.capture());
        assertThat(captor.getValue().horizonDays())
                .isEqualTo(ForecastRequest.DEFAULT_HORIZON_DAYS);
        assertThat(captor.getValue().seasonalPeriods())
                .isEqualTo(ForecastRequest.DEFAULT_SEASONAL_PERIODS);
    }

    @Test
    void rejectsSkuWithTooLittleHistoryWithoutCallingMlService() {
        when(demandHistoryRepository.findBySkuOrderByDemandDateAsc(SKU))
                .thenReturn(List.of(history(LocalDate.of(2026, 7, 1), 10)));

        assertThatThrownBy(() -> forecastService.forecast(SKU, new ForecastRequest(7, 7)))
                .isInstanceOf(InsufficientHistoryException.class)
                .hasMessageContaining(SKU);

        verify(mlServiceClient, never()).forecast(any());
    }

    private static DemandHistory history(LocalDate date, double quantity) {
        return DemandHistory.builder().sku(SKU).demandDate(date).quantity(quantity).build();
    }

    /**
     * A minimal well-formed forecast.
     *
     * <p>These tests are about what {@code ForecastService} sends and passes back, not about
     * the contents of the ML response, so everything beyond the identifying fields is left
     * empty rather than invented.
     */
    private static ForecastResponse forecastResponse(String model, int observations) {
        return new ForecastResponse(
                SKU, model, "best walk-forward MAPE", "daily", observations, false,
                List.of(), List.of(), List.of(), List.of());
    }
}
