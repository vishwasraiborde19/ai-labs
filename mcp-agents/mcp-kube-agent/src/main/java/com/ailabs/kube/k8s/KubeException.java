package com.ailabs.kube.k8s;

/** A failed Kubernetes API call, carrying the HTTP status (404, 403, 409, 422, 429, ...). */
public class KubeException extends IllegalStateException {

    private final int status;

    public KubeException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
