package com.tradewind.portfolio.reconciliation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal operations endpoint; {@code ok == false} means the books need investigating. */
@RestController
class ReconciliationController {

    private final ReconciliationService reconciliation;

    ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @GetMapping("/internal/reconciliation")
    ReconciliationService.Report reconcile() {
        return reconciliation.run();
    }
}
