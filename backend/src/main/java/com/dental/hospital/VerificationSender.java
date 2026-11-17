package com.dental.hospital;

public interface VerificationSender {
    void send(String purpose, String destination, String code);
}
