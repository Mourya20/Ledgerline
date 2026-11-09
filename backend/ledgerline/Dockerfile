# Multi-stage Dockerfile for LedgerLine Spring Boot Backend
# Build stage
FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /workspace

# Copy Maven wrapper and POM first for dependency caching
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

RUN chmod +x ./mvnw && ./mvnw dependency:go-offline -B

# Copy source code and build jar
COPY src src
RUN ./mvnw clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Run as non-root user for container security
RUN addgroup -S ledgerline && adduser -S ledgerline -G ledgerline
USER ledgerline:ledgerline

COPY --from=builder /workspace/target/ledgerline-*.jar app.jar

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
