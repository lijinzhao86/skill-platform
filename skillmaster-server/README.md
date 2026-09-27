# skillmaster server

The API and authorization server — one process serving the read API, the authorization
server, and blob storage ([technical design](../../docs/versions/v1-hosting/technical-design.md)
§2.2). **Java 25 (LTS) + Spring Boot 4 / Spring Security 7**
([ADR 0011](../../docs/decisions/0011-server-and-cli-stack.md)).

**Framework only.** It builds and starts, and the sole endpoint is `/actuator/health`.
None of the API, the AS, or storage is implemented yet — the phasing is in
[`technical-design.md`](../../docs/versions/v1-hosting/technical-design.md) §7.

## Toolchain

Needs **JDK 25**. `./mvnw` downloads its own Maven, so Maven itself needs no install:
CI, a laptop and the image all resolve the same Maven version from
`.mvn/wrapper/maven-wrapper.properties`. Its CI is
[`.github/workflows/server.yml`](../.github/workflows/server.yml), **currently disabled on
GitHub**.

## Build and run

```bash
./mvnw verify            # compile + tests
./mvnw spring-boot:run   # serves http://localhost:8080
curl localhost:8080/actuator/health
```

Maven picks its JDK from the environment, so on a machine with more than one installed,
point `JAVA_HOME` at 25 explicitly:

```bash
export JAVA_HOME=$(brew --prefix openjdk@25)   # Homebrew, macOS
```

## Dockerfile

Multi-stage: `eclipse-temurin:25-jdk` compiles, `eclipse-temurin:25-jre` runs. The build
context is the repository root, because the image also carries `gateway/`.

**Unverified.** This image has never been built — the Docker daemon was not running and
Docker Hub was unreachable from the machine where the file was written, so even the
`eclipse-temurin:25` tags are unconfirmed.

## reference-python/

The pre-Java baseline, archived: a design reference for the Java rewrite, not built and
not tested. Its defects are catalogued in
[`known-issues.md`](../../docs/versions/v1-hosting/known-issues.md).
