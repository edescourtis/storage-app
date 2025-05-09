# Stage 1: build the fat JAR
FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /workspace

# Copy only the pom first to leverage Docker cache for dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: run the application
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy the built JAR from the builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Expose port and set memory limits at runtime via Docker flags
EXPOSE 8080
ENTRYPOINT ["java","-Xms512m","-Xmx800m","-jar","/app/app.jar"]
