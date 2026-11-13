package com.ailabs.orchestrator.chain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A saved, ordered list of steps across agents; each step can use earlier steps' output. */
public class Chain {
    public String id;
    public String name;
    public String description;
    public List<ChainStep> steps = new ArrayList<>();
    public Instant updatedAt;

    public void validate() {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("give the chain a name");
        if (steps == null || steps.isEmpty()) throw new IllegalArgumentException("add at least one step");
        for (int i = 0; i < steps.size(); i++) steps.get(i).validate(i + 1);
    }
}
