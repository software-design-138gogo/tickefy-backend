param()

$envFile = ".env"

if (Test-Path $envFile) {
    Write-Host "Loading environment variables from $envFile..." -ForegroundColor Cyan
    Get-Content $envFile | Where-Object { $_ -match '^[^#]+=' } | ForEach-Object {
        $name, $value = $_.Split('=', 2)
        [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), "Process")
    }
} else {
    Write-Host "Warning: $envFile not found. Running with default OS environment variables." -ForegroundColor Yellow
}

Write-Host "Starting Spring Boot..." -ForegroundColor Green
.\mvnw spring-boot:run
