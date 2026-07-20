package com.shvoy.onboarding.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/onboarding")
class OnboardingPingController {

    @GetMapping("/ping")
    Map<String, String> ping() {
        return Map.of("module", "onboarding", "status", "ok");
    }
}
