package com.bigdata.scriptbox.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping({"/", "/index"})
    public String index() { return "execute"; }

    @GetMapping("/tenants")
    public String tenants() { return "tenants"; }

    @GetMapping("/scripts")
    public String scripts() { return "scripts"; }

    @GetMapping("/scripts/edit")
    public String scriptEdit() { return "script-edit"; }

    @GetMapping("/history")
    public String history() { return "history"; }
}