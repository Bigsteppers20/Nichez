# The API as a container image (Render, Cloud Run, Oracle). Build context is backend/.
#   docker build -t nichez-api backend

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Dependencies first, so a code-only change reuses the cached layer.
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 nichez
WORKDIR /app
COPY --from=build /src/target/api-*.jar app.jar
USER nichez
# prod reads real environment variables only and never imports .env.
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
# Render sets PORT; 8080 elsewhere. The heap is sized from the container's memory limit.
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=75 -Dserver.port=${PORT:-8080} -jar app.jar"]
