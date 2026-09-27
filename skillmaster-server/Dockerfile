# The wrapper downloads its own Maven, so this stage needs no toolchain beyond a JDK —
# and CI, a laptop and this image all resolve the same Maven version from
# .mvn/wrapper/maven-wrapper.properties.
FROM eclipse-temurin:25-jdk AS build

WORKDIR /build

COPY skillmaster-server/ ./skillmaster-server/
RUN cd skillmaster-server && ./mvnw -B -DskipTests package

# A JRE, not a JDK: nothing in the image compiles.
FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=build /build/skillmaster-server/target/*.jar app.jar

# The gateway skill is served from the API (/.well-known/... and /gateway/SKILL.md),
# so the image has to carry its source.
COPY gateway/ ./gateway/

RUN useradd --create-home --uid 10001 skill
USER skill

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
