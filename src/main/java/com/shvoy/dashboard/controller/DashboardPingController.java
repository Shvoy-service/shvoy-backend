package com.shvoy.dashboard.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
class DashboardPingController {

    @GetMapping("/ping")
    Map<String, String> ping() {
        return Map.of("module", "dashboard", "status", "ok");
    }
}
