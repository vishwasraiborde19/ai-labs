package com.ailabs.orchestrator.web;

import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.ailabs.orchestrator.llm.Llm;
import com.ailabs.orchestrator.mcp.McpException;

/** Turns failures into {@code {"error": ...}} with a meaningful status. */
@RestControllerAdvice(basePackageClasses = ApiErrors.class)
public class ApiErrors {

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, Object>> missing(NoSuchElementException e) {
        return ResponseEntity.status(404).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, Object>> bad(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler({IllegalStateException.class, McpException.class, Llm.LlmException.class})
    ResponseEntity<Map<String, Object>> upstream(RuntimeException e) {
        return ResponseEntity.status(502).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
