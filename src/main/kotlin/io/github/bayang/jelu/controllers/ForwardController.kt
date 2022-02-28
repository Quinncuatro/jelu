package io.github.bayang.jelu.controllers

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
public class ForwardController {
    @GetMapping(value = ["/error"])
    fun forward(): String {
        return "forward:/"
    }
}
