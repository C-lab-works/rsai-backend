FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew :gate-core:shadowJar --no-daemon -q

FROM eclipse-temurin:21-jre AS cds
WORKDIR /app
COPY --from=build /app/gate-core/build/libs/*-all.jar app.jar
ARG ENABLE_CDS=false
RUN if [ "$ENABLE_CDS" = "true" ]; then \
      java -XX:ArchiveClassesAtExit=app-cds.jsa -jar app.jar || true; \
    else \
      touch app-cds.jsa; \
    fi

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/gate-core/build/libs/*-all.jar app.jar
COPY --from=cds /app/app-cds.jsa app-cds.jsa
ARG ENABLE_CDS=false
EXPOSE 8080
CMD if [ -s app-cds.jsa ]; then \
      exec java -XX:SharedArchiveFile=app-cds.jsa -jar app.jar; \
    else \
      exec java -jar app.jar; \
    fi
