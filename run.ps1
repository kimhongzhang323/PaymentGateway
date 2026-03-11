# Startup script for KimPay Payment Gateway
Write-Host "--- Starting KimPay Infrastructure (Docker) ---" -ForegroundColor Cyan
docker-compose up -d

Write-Host "`nWaiting for infrastructure to be ready..." -ForegroundColor Yellow
Start-Sleep -Seconds 10

Write-Host "`n--- Building Application ---" -ForegroundColor Cyan
.\mvnw.cmd clean package -DskipTests

Write-Host "`n--- Running Payment Application ---" -ForegroundColor Green
# Environment variables matching docker-compose
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/payment_gateway"
$env:SPRING_DATASOURCE_USERNAME="postgres"
$env:SPRING_DATASOURCE_PASSWORD="admin"
$env:REDIS_HOST="localhost"
$env:KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
$env:PAYMENT_ENCRYPTION_KEY_BASE64="YXNkZmFzZGZhc2RmYXNkZmFzZGZhc2RmYXNkZmFzZGY=" # Demo key

java -jar payment-api/target/payment-api-0.0.1-SNAPSHOT.jar
