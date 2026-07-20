package com.shvoy.purchaseorders.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/purchaseorders")
class PurchaseOrdersPingController {

    @GetMapping("/ping")
    Map<String, String> ping() {
        return Map.of("module", "purchaseorders", "status", "ok");
    }
}
