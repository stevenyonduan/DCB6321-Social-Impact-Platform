# Build context: backend/ (Render Root Directory).
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/ src/
RUN ./mvnw -B -ntp verify && cp target/sip-backend-*.jar /build/app.jar

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN groupadd --gid 10001 sip && useradd --uid 10001 --gid sip --create-home sip
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/app.jar ./app.jar
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
