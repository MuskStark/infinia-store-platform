# ADR-001: Modular monolith on an independent version line

Status: Accepted (design §5.1, §18.1)

The store platform is a single deployable Spring Boot 4.1.x application with strict Maven
module boundaries (`store-contract` → `store-domain` → `store-infrastructure` / `store-scanner`
→ `store-application`) instead of upfront microservices. Cross-module repository access is
impossible by construction — modules talk through ports, domain services and outbox events.
The scanner already runs on its own executor and the outbox relay isolates async work, so a
future split into scan workers is a deployment change, not a rewrite.

Version line `0.1.x` is independent from the FengYu host (`4.0.0-beta.5`) and the plugin
toolchain (`2.1.0`).

**Deviation note:** Spring Modulith (listed in §5.2) currently publishes only 2.1.x against
Boot 3.x; no Boot 4.1-compatible release exists. Maven module separation enforces the same —
stronger — boundaries; adopting Modulith is a drop-in follow-up once its Boot 4 line ships.
