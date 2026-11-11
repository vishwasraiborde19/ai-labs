package com.ailabs.vmadmin.agent;

/**
 * A call to a VM agent failed. {@code status} is the HTTP status to hand back to the UI: the agent's
 * own 4xx when it refused (missing file, permission denied, ...), 502 when it could not be reached.
 */
public class AgentException extends RuntimeException {

    private final int status;

    public AgentException(int status, String message) {
        super(message);
        this.status = status;
    }

    public AgentException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
