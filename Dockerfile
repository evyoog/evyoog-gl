# Use a base image with Java (e.g., OpenJDK)
#FROM openjdk:21-jdk-alpine
FROM openjdk:23-jdk
# Set the working directory in the container
WORKDIR /app

# Copy the JAR file produced by your Spring Boot build into the container
COPY target/Item-0.0.1-SNAPSHOT.jar app.jar

# Expose the port that the Spring Boot app will run on
EXPOSE 8080

# Command to run the JAR file
ENTRYPOINT ["java", "-jar", "app.jar"]
