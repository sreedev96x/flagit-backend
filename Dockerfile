# Stage 1: Build the application using Maven
FROM openjdk:17-jdk-slim AS build

WORKDIR /app
COPY .mvn/ .mvn
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline
COPY src ./src

# This command builds the .jar file inside the container
RUN ./mvnw package -DskipTests

# Stage 2: Create the final, lightweight image
FROM openjdk:17-jdk-slim

WORKDIR /app

# Copy the necessary files from the build stage
COPY --from=build /app/target/flagit-backend-1.0-SNAPSHOT.jar app.jar
COPY keystore.jks keystore.jks
COPY serviceAccountKey.json serviceAccountKey.json

EXPOSE 4567
CMD ["java", "-jar", "app.jar"]