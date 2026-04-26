FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew :gate-core:shadowJar --no-daemon -q

# CDS training: run the JAR once so the JVM loads and archives all classes.
# DB connection will fail on first run — that's expected; the archive is still written on JVM exit.
FROM eclipse-temurin:21-jre AS cds
WORKDIR /app
COPY --from=build /app/gate-core/build/libs/*-all.jar app.jar
RUN java -XX:ArchiveClassesAtExit=app-cds.jsa -jar app.jar || true

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/gate-core/build/libs/*-all.jar app.jar
COPY --from=cds /app/app-cds.jsa app-cds.jsa
EXPOSE 8080
CMD ["java", "-XX:SharedArchiveFile=app-cds.jsa", "-jar", "app.jar"]
