# syntax=docker/dockerfile:1

# Unpack the boot jar so that dependencies and application code land in separate layers.
FROM eclipse-temurin:25-jre AS extract
ARG JAR_FILE=build/libs/v31-compliance-service-0.2.0-SNAPSHOT.jar
WORKDIR /extract
COPY ${JAR_FILE} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre
RUN useradd --system --create-home --uid 10001 v31
WORKDIR /application
COPY --from=extract --chown=v31:v31 /extract/extracted/dependencies/ ./
COPY --from=extract --chown=v31:v31 /extract/extracted/spring-boot-loader/ ./
COPY --from=extract --chown=v31:v31 /extract/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=v31:v31 /extract/extracted/application/ ./
USER v31
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "application.jar"]
