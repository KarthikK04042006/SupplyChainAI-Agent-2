package com.supply.demandforecast.service;

import com.supply.demandforecast.dto.ImportResult;
import com.supply.demandforecast.entity.DemandHistory;
import com.supply.demandforecast.repository.DemandHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DemandImportServiceTest {

    @Mock
    private DemandHistoryRepository demandHistoryRepository;

    @InjectMocks
    private DemandImportService demandImportService;

    @Test
    void importsWellFormedRows() {
        noExistingRows();
        echoSavedRows();

        ImportResult result = importCsv("""
                sku,date,quantity
                WIDGET-001,2026-07-01,10
                WIDGET-001,2026-07-02,12.5
                WIDGET-002,2026-07-01,3
                """);

        assertThat(result.rowsParsed()).isEqualTo(3);
        assertThat(result.inserted()).isEqualTo(3);
        assertThat(result.updated()).isZero();
        assertThat(result.rejected()).isZero();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void toleratesColumnsInAnyOrder() {
        noExistingRows();
        echoSavedRows();

        ImportResult result = importCsv("""
                quantity,sku,date
                10,WIDGET-001,2026-07-01
                """);

        assertThat(result.rowsParsed()).isEqualTo(1);
        assertThat(result.rejected()).isZero();
    }

    @Test
    void rejectsBadRowsButKeepsGoodOnes() {
        noExistingRows();
        echoSavedRows();

        ImportResult result = importCsv("""
                sku,date,quantity
                WIDGET-001,2026-07-01,10
                WIDGET-001,not-a-date,5
                WIDGET-001,2026-07-03,abc
                WIDGET-001,2026-07-04,-2
                ,2026-07-05,7
                WIDGET-001,2026-07-06,8
                """);

        assertThat(result.rowsParsed()).isEqualTo(2);
        assertThat(result.rejected()).isEqualTo(4);
        assertThat(result.errors()).hasSize(4);
        assertThat(result.errors().get(1)).contains("not a number");
        assertThat(result.errors().get(2)).contains("negative");
        assertThat(result.errors().get(3)).contains("sku");
    }

    @Test
    void collapsesDuplicateDatesWithinOneFile() {
        noExistingRows();
        echoSavedRows();

        ImportResult result = importCsv("""
                sku,date,quantity
                WIDGET-001,2026-07-01,10
                WIDGET-001,2026-07-01,25
                """);

        assertThat(result.rowsParsed()).isEqualTo(2);
        assertThat(result.inserted()).isEqualTo(1);
    }

    @Test
    void updatesExistingDayInsteadOfDuplicating() {
        DemandHistory existing = DemandHistory.builder()
                .id(7L).sku("WIDGET-001").demandDate(LocalDate.of(2026, 7, 1)).quantity(10).build();
        when(demandHistoryRepository.findBySkuAndDemandDate(anyString(), any()))
                .thenReturn(Optional.of(existing));
        echoSavedRows();

        ImportResult result = importCsv("""
                sku,date,quantity
                WIDGET-001,2026-07-01,42
                """);

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.inserted()).isZero();
        assertThat(existing.getQuantity()).isEqualTo(42);
    }

    @Test
    void rejectsEmptyUpload() {
        MockMultipartFile empty =
                new MockMultipartFile("file", "demand.csv", "text/csv", new byte[0]);

        assertThatThrownBy(() -> demandImportService.importCsv(empty))
                .isInstanceOf(CsvImportException.class)
                .hasMessageContaining("empty");
    }

    private ImportResult importCsv(String content) {
        return demandImportService.importCsv(new MockMultipartFile(
                "file", "demand.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8)));
    }

    private void noExistingRows() {
        when(demandHistoryRepository.findBySkuAndDemandDate(anyString(), any()))
                .thenReturn(Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private void echoSavedRows() {
        lenient().when(demandHistoryRepository.saveAll(any()))
                .thenAnswer(invocation -> List.copyOf((List<DemandHistory>) invocation.getArgument(0)));
    }
}
