package com.fraud.detection.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app.fraud.rules")
public class FraudRulesProperties {

    private BigDecimal highAmountThreshold = new BigDecimal("10000.00");
    private int velocityMaxTransactions = 5;
    private Duration velocityWindow = Duration.ofMinutes(1);
    private Duration countryChangeWindow = Duration.ofMinutes(30);
    private List<String> highRiskMerchants = new ArrayList<>();

    public BigDecimal getHighAmountThreshold() {
        return highAmountThreshold;
    }

    public void setHighAmountThreshold(BigDecimal highAmountThreshold) {
        this.highAmountThreshold = highAmountThreshold;
    }

    public int getVelocityMaxTransactions() {
        return velocityMaxTransactions;
    }

    public void setVelocityMaxTransactions(int velocityMaxTransactions) {
        this.velocityMaxTransactions = velocityMaxTransactions;
    }

    public Duration getVelocityWindow() {
        return velocityWindow;
    }

    public void setVelocityWindow(Duration velocityWindow) {
        this.velocityWindow = velocityWindow;
    }

    public Duration getCountryChangeWindow() {
        return countryChangeWindow;
    }

    public void setCountryChangeWindow(Duration countryChangeWindow) {
        this.countryChangeWindow = countryChangeWindow;
    }

    public List<String> getHighRiskMerchants() {
        return highRiskMerchants;
    }

    public void setHighRiskMerchants(List<String> highRiskMerchants) {
        this.highRiskMerchants = highRiskMerchants;
    }
}
