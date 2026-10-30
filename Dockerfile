FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
RUN sed -i 's/\r$//' mvnw && sh ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65.0 -XX:+ExitOnOutOfMemoryError" \
    SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2 \
    SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=0 \
    SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT=30000

COPY --from=build --chown=10001:10001 /workspace/target/where-do-i-go-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
