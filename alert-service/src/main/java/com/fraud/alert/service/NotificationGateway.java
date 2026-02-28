package com.fraud.alert.service;

import com.fraud.alert.model.Alert;

/**
 * Gateway for sending fraud alert notifications through one or more channels.
 */
public interface NotificationGateway {

    void notifyFraud(Alert alert);
}
