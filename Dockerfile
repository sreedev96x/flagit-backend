# 1. Start with a Java environment
FROM openjdk:17-jdk-slim

# 2. Set the working directory
WORKDIR /app

# 3. Copy the pre-built .jar file
COPY target/flagit-backend-1.0-SNAPSHOT.jar app.jar

# 4. Copy the necessary keys
COPY keystore.jks keystore.jks
COPY serviceAccountKey.json serviceAccountKey.json

# 5. Expose the port
EXPOSE 4567

# 6. Run the application
CMD ["java", "-jar", "app.jar"]