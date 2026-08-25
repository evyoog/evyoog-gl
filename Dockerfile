# Base image with Java 21 — matches <java.version>21</java.version> in pom.xml.
# NOTE: the `openjdk` Docker Hub repo is deprecated and has no 23-jdk tag
# (`manifest unknown` at build time). eclipse-temurin is the maintained successor.
FROM eclipse-temurin:21-jre
# Set the working directory in the container
WORKDIR /app

# Copy the JAR file produced by your Spring Boot build into the container
COPY target/evyoog-gl-0.0.1-SNAPSHOT.jar app.jar

# Expose the port that the Spring Boot app will run on
EXPOSE 8080

# Command to run the JAR file
ENTRYPOINT ["java", "-jar", "app.jar"]
