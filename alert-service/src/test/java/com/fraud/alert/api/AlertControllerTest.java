package com.fraud.alert.api;

import com.fraud.alert.model.Alert;
import com.fraud.alert.repository.AlertRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AlertController.class)
class AlertControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AlertRepository alertRepository;

    @MockBean
    private AlertMapper mapper;

    @Test
    void listAlertsShouldReturnMappedAlertsInRepositoryOrder() throws Exception {
        Alert first = new Alert(
                "a-2",
                "tx-2",
                "user-2",
                70,
                "HIGH_AMOUNT",
                Instant.parse("2026-01-01T10:02:00Z")
        );
        Alert second = new Alert(
                "a-1",
                "tx-1",
                "user-1",
                55,
                "HIGH_VELOCITY",
                Instant.parse("2026-01-01T10:01:00Z")
        );

        AlertResponse firstResponse = new AlertResponse(
                "a-2",
                "tx-2",
                "user-2",
                70,
                List.of("HIGH_AMOUNT"),
                Instant.parse("2026-01-01T10:02:00Z")
        );
        AlertResponse secondResponse = new AlertResponse(
                "a-1",
                "tx-1",
                "user-1",
                55,
                List.of("HIGH_VELOCITY"),
                Instant.parse("2026-01-01T10:01:00Z")
        );

        when(alertRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(first, second));
        when(mapper.toResponse(first)).thenReturn(firstResponse);
        when(mapper.toResponse(second)).thenReturn(secondResponse);

        mockMvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("a-2"))
                .andExpect(jsonPath("$[0].transactionId").value("tx-2"))
                .andExpect(jsonPath("$[0].reasons[0]").value("HIGH_AMOUNT"))
                .andExpect(jsonPath("$[1].id").value("a-1"))
                .andExpect(jsonPath("$[1].transactionId").value("tx-1"));

        verify(alertRepository).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void listAlertsByUserShouldReturnOnlyMappedUserAlerts() throws Exception {
        Alert userAlert = new Alert(
                "a-3",
                "tx-3",
                "user-42",
                88,
                "HIGH_AMOUNT,HIGH_RISK_MERCHANT",
                Instant.parse("2026-01-01T10:03:00Z")
        );

        AlertResponse userAlertResponse = new AlertResponse(
                "a-3",
                "tx-3",
                "user-42",
                88,
                List.of("HIGH_AMOUNT", "HIGH_RISK_MERCHANT"),
                Instant.parse("2026-01-01T10:03:00Z")
        );

        when(alertRepository.findByUserIdOrderByCreatedAtDesc("user-42")).thenReturn(List.of(userAlert));
        when(mapper.toResponse(userAlert)).thenReturn(userAlertResponse);

        mockMvc.perform(get("/api/v1/alerts/users/user-42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("a-3"))
                .andExpect(jsonPath("$[0].userId").value("user-42"))
                .andExpect(jsonPath("$[0].riskScore").value(88))
                .andExpect(jsonPath("$[0].reasons[1]").value("HIGH_RISK_MERCHANT"));

        verify(alertRepository).findByUserIdOrderByCreatedAtDesc("user-42");
    }
}
