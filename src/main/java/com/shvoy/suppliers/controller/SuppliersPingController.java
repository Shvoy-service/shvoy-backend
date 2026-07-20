package com.shvoy.suppliers.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/suppliers")
class SuppliersPingController {

    @GetMapping("/ping")
    Map<String, String> ping() {
        return Map.of("module", "suppliers", "status", "ok");
    }
}
