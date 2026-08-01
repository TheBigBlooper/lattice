# Orders service - provisional container image.
#
# Provisional base (eclipse-temurin 21 JRE) per the orders design. Runs the self-contained fat jar
# built by `./mvnw -pl services/orders package` (target/orders-fat.jar), as a non-root user, with a
# liveness HEALTHCHECK against the unversioned /health probe. The build context is this module dir
# (deploy/docker/docker-compose.yml uses build: ../../services/orders), so target/ must be built
# first. A later ticket may switch this to a multi-stage build that compiles inside Docker.
FROM eclipse-temurin:21-jre

# curl is used only by the HEALTHCHECK below; installed in one layer, then the apt lists are dropped.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

# Run as a non-root user (least privilege); the JRE base does not create one.
RUN useradd --system --create-home --uid 10001 lattice
# NUMERIC, deliberately. Kubernetes cannot verify that a NAMED user is non-root, so a pod with
# runAsNonRoot set refuses to start: "image has non-numeric user (lattice), cannot verify user is
# non-root". The name is what the useradd above created; the number is what the platform can check.
USER 10001
WORKDIR /home/lattice

# The fat jar carries the app + every dependency and its manifest Main-Class (io.lattice.orders.MainVerticle).
COPY target/orders-fat.jar app.jar

# Config comes from the environment (the shared LatticeConfig loader): HTTP_PORT, ELASTICSEARCH_URL, ...
ENV HTTP_PORT=8080
EXPOSE 8080

# Liveness: the process is up once /health answers 200. Readiness (ES reachable) is probed separately
# by the orchestrator at /readiness.
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=6 \
    CMD curl -fsS "http://localhost:${HTTP_PORT}/health" || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
