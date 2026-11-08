package com.ledgerline.auth;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    @GetMapping("/test")
    public Map<String, String> test() {
        return Map.of("status", "admin-access-granted");
    }

    @GetMapping("/summary")
    public Map<String, String> summary() {
        return Map.of("system", "operational");
    }
}
