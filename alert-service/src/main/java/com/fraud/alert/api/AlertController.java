package com.fraud.alert.api;

import com.fraud.alert.repository.AlertRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertRepository alertRepository;
    private final AlertMapper mapper;

    public AlertController(AlertRepository alertRepository, AlertMapper mapper) {
        this.alertRepository = alertRepository;
        this.mapper = mapper;
    }

    @GetMapping
    public List<AlertResponse> listAlerts() {
        return alertRepository.findAllByOrderByCreatedAtDesc().stream().map(mapper::toResponse).toList();
    }

    @GetMapping("/users/{userId}")
    public List<AlertResponse> listAlertsByUser(@PathVariable String userId) {
        return alertRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(mapper::toResponse).toList();
    }
}
