---
name: api-best-practices
description: HTTP/REST API design and implementation best practices for the ai-labs Spring Boot 3 services (platform-cloud2 config/discovery servers, infra-canary, the Java MCP agents, command-center) and for MCP tool interfaces. Use this whenever designing, adding or reviewing an endpoint, controller, request/response model, error handling, validation, pagination, versioning, OpenAPI docs, actuator exposure, outbound HTTP clients (timeouts, retries), tracing/metrics, or an MCP tool's name and parameters — even if the user just says "add an endpoint for X" or "review my controller".
---

# API best practices (ai-labs)

Goal: APIs that are predictable for callers, safe to retry, honest about failure, observable in Grafana,
and hard to misuse. Stack: Spring Boot 3.x, Java 17/21, Micrometer, actuator; observability on
192.168.1.137 (Prometheus :9090, OTLP :4318); SSO via Keycloak realm `lab` (https://192.168.1.143:8443).

## Design

**Resources and methods.** Nouns in plural, verbs from HTTP: `GET /orders`, `GET /orders/{id}`,
`POST /orders`, `PUT|PATCH /orders/{id}`, `DELETE /orders/{id}`. Actions that aren't CRUD become
sub-resources (`POST /orders/{id}/cancellation`) rather than `/cancelOrder`. Lower-case, hyphenated paths;
camelCase JSON fields; ISO-8601 UTC timestamps (`Instant`); IDs as opaque strings.

**Status codes say what happened** — callers, retries and dashboards depend on them:

| Situation | Code |
|---|---|
| read OK / update OK with body | 200 |
| created | 201 + `Location` header |
| accepted, processing async | 202 + a status URL |
| success, no body | 204 |
| malformed / failed validation | 400 (field errors in body) |
| not authenticated / not allowed | 401 / 403 |
| not found | 404 |
| conflict, version mismatch | 409 / 412 |
| rate limited | 429 + `Retry-After` |
| bug | 500 (never leak the stack trace) |
| dependency down / overloaded | 503 (+ `Retry-After`) or 502/504 for upstream errors/timeouts |

Never return 200 with `{"error": ...}`: monitoring counts `http_server_requests_seconds_count{status="5xx"}`
and would see success.

**Safe and idempotent.** GET/HEAD never change state. PUT and DELETE are idempotent. For POSTs that create
or charge something, accept an `Idempotency-Key` header and return the first result for a repeated key —
that's what makes client retries safe.

**Collections.** Always paginate (`?page=0&size=50`, cap `size`, return `totalElements` or a `next` cursor);
offer filters and `sort` explicitly rather than open-ended query languages.

**Versioning.** Additive changes (new optional fields, new endpoints) are not breaking; clients must ignore
unknown fields. Breaking changes get a new version (`/api/v2/...`) and the old one keeps working until
callers have moved. Never repurpose a field.

**Contract.** Expose OpenAPI with springdoc (`org.springdoc:springdoc-openapi-starter-webmvc-ui`) and keep
request/response records separate from JPA entities, so internals can change without breaking callers.

## Implementation (Spring Boot)

**Errors as RFC 7807 Problem Details** — one shape everywhere:

```properties
spring.mvc.problemdetails.enabled=true
server.error.include-stacktrace=never
```
```java
@RestControllerAdvice
class ApiErrors extends ResponseEntityExceptionHandler {          // handles validation, 404, 405 ... as ProblemDetail
    @ExceptionHandler(OrderNotFound.class)
    ProblemDetail notFound(OrderNotFound e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        p.setType(URI.create("https://ai-labs/errors/order-not-found"));
        return p;
    }
}
```
Log the full exception server-side with the request/trace ID; return only a safe message to the client.

**Validate at the edge.** `spring-boot-starter-validation`, `@Valid` on `@RequestBody`, constraints on the
record (`@NotBlank`, `@Size(max=…)`, `@Positive`), `@Validated` on the controller for path/query params.
Clamp numeric inputs that size work (`count`, `size`, `lines`) — infra-canary's `/log` caps at 10 000 for
that reason. Limit request body size (`spring.servlet.multipart.max-request-size`, server limits).

**Outbound calls always have timeouts.** A missing timeout turns a slow dependency into thread exhaustion
in this service too.
```java
HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();      // + per-request .timeout(...)
RestClient.builder().requestFactory(factoryWithConnectAndReadTimeouts)...
```
Retry only idempotent calls, with exponential backoff + jitter and a small cap (Resilience4j `@Retry`);
add a circuit breaker for dependencies that fail slowly. Never retry a non-idempotent POST without an
idempotency key.

**Health and probes.** `management.endpoint.health.probes.enabled=true`. Liveness reflects only this JVM;
readiness may include critical dependencies via health groups:
```properties
management.endpoint.health.group.readiness.include=readinessState,db
```
Expose only what is needed (`management.endpoints.web.exposure.include=health,info,prometheus`); never
`*` on a reachable port — `env`, `heapdump`, `threaddump` leak secrets. Put actuator on a separate
management port if the API is exposed beyond the cluster.

**Observability.** `micrometer-registry-prometheus` for `/actuator/prometheus`; add business counters/timers
with low-cardinality tags (never user IDs or raw paths as tags). Tracing: `micrometer-tracing-bridge-otel`
+ `opentelemetry-exporter-otlp` to `http://192.168.1.137:4318/v1/traces`; include `traceId` in log lines
(`logging.pattern.correlation`). Accept and propagate `traceparent`.

**Shutdown.** `server.shutdown=graceful` so in-flight requests finish on rollout.

**Security hooks.** Authenticate with Keycloak OIDC (`spring-boot-starter-oauth2-resource-server`,
`spring.security.oauth2.resourceserver.jwt.issuer-uri=https://192.168.1.143:8443/realms/lab`), authorise
by role/scope per endpoint, deny by default. Dangerous operational endpoints (like infra-canary's `/chaos`)
are disabled by default, gated by config, and compare tokens in constant time
(`MessageDigest.isEqual`). Details: the `security-best-practices` skill.

**Tests.** MockMvc/WebTestClient tests per endpoint covering: happy path, validation 400, not found,
auth 401/403, and the error body shape. Assert status codes, not just bodies.

## MCP tool interfaces (the agents in mcp-agents/)

An MCP tool is an API whose caller is a model, so clarity matters even more:

- Name tools as verb_noun (`list_pods`, `run_command`), describe *when* to use each, and give every
  parameter a type, description and bounds; prefer enums over free strings.
- Separate read-only tools from mutating ones, and mark destructive ones clearly so clients can require
  approval; support a dry-run where it is cheap.
- Return structured, bounded output (truncate long logs with a note), with errors that say what to do next.
- Never return secret values (mcp-kube-agent hides Secret data — keep it that way); audit every mutating call.
- Make long work a job (`start_job` / `job_status`) instead of a call that times out.

## Review output

Report findings as **must fix** (wrong semantics, unsafe, breaks callers), **should fix**, **nit** — each
with file:line, the problem, why it matters to a caller or operator, and the corrected code.
