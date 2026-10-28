# ADR-001: Modular monolith

- **Status:** Accepted in project bible v0.2 (implementation is starting).
- **Context:** Three team members need a small, understandable system with a
  transactional complaint and audit trail, not independent services to operate.
- **Decision:** One Spring Boot application, organized by feature. Services own
  their business rules; cross-feature code calls services rather than reaching
  into another feature's repository. Only the future workflow module will write
  complaint status and events.
- **Consequences:** One deployment and shared transactions make the first demo
  simpler. Feature boundaries require review and tests; modules can be split
  later if the team has evidence that this is necessary.
