package com.ailabs.orchestrator.run;

import com.ailabs.orchestrator.llm.Llm;

/** Handed to the work of one run: record events, honour cancellation, ask a human. */
public final class RunContext {

    /** Thrown when the operator cancels; stops the run wherever it is. */
    public static final class Cancelled extends RuntimeException {
        public Cancelled() {
            super("cancelled by the operator");
        }
    }

    private final Run run;
    private final RunService service;

    RunContext(Run run, RunService service) {
        this.run = run;
        this.service = service;
    }

    public Run run() {
        return run;
    }

    public void event(String type, String agent, String title, String detail) {
        service.event(run, type, agent, title, detail, null);
    }

    public void checkCancelled() {
        if (run.cancelRequested) throw new Cancelled();
    }

    /** Blocks until a person approves or denies in the UI (or the approval times out: denied). */
    public boolean approve(String agent, String title, String detail) {
        return service.awaitApproval(run, agent, title, detail);
    }

    public synchronized void usage(long in, long out) {
        synchronized (run) {
            run.inputTokens += in;
            run.outputTokens += out;
        }
    }

    /** LLM progress callbacks for an agent (or the orchestrator when {@code agent} is null). */
    public Llm.Listener listener(String agent) {
        return new Llm.Listener() {
            @Override
            public void say(String text) {
                event("say", agent, null, text);
            }

            @Override
            public void usage(long inputTokens, long outputTokens) {
                RunContext.this.usage(inputTokens, outputTokens);
            }

            @Override
            public void checkCancelled() {
                RunContext.this.checkCancelled();
            }
        };
    }
}
