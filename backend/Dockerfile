# ── Stage 1: Build ─────────────────────────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Cache Maven dependencies layer
COPY pom.xml .
RUN mvn dependency:go-offline -B || true

# Copy source code and build executable Spring Boot JAR
COPY src ./src
RUN mvn clean package -DskipTests -B

# ── Stage 2: Minimal Runtime ──────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Install git (required for repository cloning and JGit operations)
RUN apk add --no-cache git

# Create dedicated non-root application user for container security
RUN addgroup -S codecompass && adduser -S codecompass -G codecompass

# Create persistent repository cache directory and grant ownership
RUN mkdir -p /app/.repo-cache && chown -R codecompass:codecompass /app

USER codecompass

# Copy the packaged Spring Boot executable JAR from the builder stage
COPY --from=build /app/target/codecompass-0.0.1-SNAPSHOT.jar app.jar

# Dynamic port binding: Render automatically provides $PORT (fallback to 8080)
ENV PORT=8080
EXPOSE 8080

# Production memory guard:
# Render free tier provides 512MB total RAM.
# -XX:MaxRAMPercentage=75.0 reserves 25% of memory for native OS/JVM structures,
# preventing exit-code 137 (OOM Killer) during AST parsing and ingestion.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
