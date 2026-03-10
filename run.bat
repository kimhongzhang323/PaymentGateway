@echo off
echo --- Starting KimPay Infrastructure (Docker) ---
docker-compose up -d

echo Waiting for infrastructure to be ready...
timeout /t 10 /nobreak

echo --- Building Application ---
call mvnw.cmd clean package -DskipTests

echo --- Running Payment Application ---
set SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/payment_gateway
set SPRING_DATASOURCE_USERNAME=postgres
set SPRING_DATASOURCE_PASSWORD=password
set REDIS_HOST=localhost
set KAFKA_BOOTSTRAP_SERVERS=localhost:9092
set PAYMENT_ENCRYPTION_KEY_BASE64=YXNkZmFzZGZhc2RmYXNkZmFzZGZhc2RmYXNkZmFzZGY=

java -jar payment-api/target/payment-api-0.0.1-SNAPSHOT.jar
