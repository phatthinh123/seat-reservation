package com.linkz.mockpayment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.concurrent.atomic.AtomicBoolean;

@CrossOrigin(origins = "http://localhost:4200")
@RestController
public class SimulationController {

    private static final Logger log = LoggerFactory.getLogger(SimulationController.class);

    public static final AtomicBoolean nextFail = new AtomicBoolean(false);
    public static final AtomicBoolean nextDelay = new AtomicBoolean(false);

    @PostMapping("/simulate/fail")
    public String simulateFail() {
        nextFail.set(true);
        log.info("Simulation activated: Next payment webhook will send FAILED status");
        return "Next payment will simulate failure";
    }

    @PostMapping("/simulate/delay")
    public String simulateDelay() {
        nextDelay.set(true);
        log.info("Simulation activated: Next payment webhook will be delayed by 60 seconds");
        return "Next payment webhook will be delayed by 60 seconds";
    }
}
