package com.shvoy.containerfill.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/containerfill")
class ContainerFillPingController {

    @GetMapping("/ping")
    Map<String, String> ping() {
        return Map.of("module", "containerfill", "status", "ok");
    }
}
