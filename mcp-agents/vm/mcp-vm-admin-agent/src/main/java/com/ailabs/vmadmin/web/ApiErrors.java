package com.ailabs.vmadmin.web;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.ailabs.vmadmin.agent.AgentException;
import com.ailabs.vmadmin.fleet.FleetStore;
import com.ailabs.vmadmin.ops.Operations;

/** Turns failures into {@code {"error": ...}} with a meaningful status for every UI API controller. */
@RestControllerAdvice(basePackageClasses = ApiErrors.class)
public class ApiErrors {

    @ExceptionHandler(Operations.NeedsConfirmation.class)
    ResponseEntity<Map<String, Object>> needsConfirm(Operations.NeedsConfirmation e) {
        return ResponseEntity.status(428).body(Map.of("needsConfirm", true, "reason", e.getMessage(),
                "error", "This command " + e.getMessage() + ". Confirm to run it."));
    }

    @ExceptionHandler(AgentException.class)
    ResponseEntity<Map<String, Object>> agent(AgentException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(FleetStore.ConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(FleetStore.ConflictException e) {
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> bad(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, Object>> state(IllegalStateException e) {
        return ResponseEntity.status(502).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
