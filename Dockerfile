FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/inventory-reservation-service-*.jar app.jar
RUN useradd --system app
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
