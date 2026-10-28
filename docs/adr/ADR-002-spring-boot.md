# ADR-002: Java 21 and Spring Boot 3

- **Status:** Accepted in project bible v0.2 (implementation is starting).
- **Context:** The team knows Java and PostgreSQL and has limited time to learn
  new tools. Flutter will serve Android and web clients.
- **Decision:** Use Java 21, Spring Boot 3, PostgreSQL 16 and versioned Flyway
  migrations. Docker Compose provides a reproducible local environment.
- **Consequences:** The team can use familiar Java and explicit SQL. It must
  learn and review Spring Security, transactions and dependency updates. The
  current security configuration denies domain access; it is not an auth system.
- **Review:** Revisit the bible's escape hatch if the Week 1 deployment and
  login gate cannot be met; do not silently change the stack.
