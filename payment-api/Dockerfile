# Build stage
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

# Copy the whole project to the build container
COPY . .

# Build only the payment-api and its dependencies
RUN mvn clean install -DskipTests

# Run stage
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Copy the JAR from the build stage
# Assuming the main app is in payment-api/target/payment-api-*.jar
COPY --from=build /app/payment-api/target/payment-api-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
