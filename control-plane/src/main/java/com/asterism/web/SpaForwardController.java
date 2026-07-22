package com.asterism.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaForwardController {
    @GetMapping({
            "/", "/work-items", "/work-items/**", "/new", "/systems",
            "/models", "/agents", "/memory", "/knowledge", "/users"
    })
    public String forwardWorkbenchRoutes() {
        // BrowserRouter 的业务路由统一回退到打包后的 Workbench 入口。
        return "forward:/index.html";
    }
}
