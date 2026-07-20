package com.shvoy.payments.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
class PaymentsPingController {

    @GetMapping("/ping")
    Map<String, String> ping() {
        return Map.of("module", "payments", "status", "ok");
    }
}
