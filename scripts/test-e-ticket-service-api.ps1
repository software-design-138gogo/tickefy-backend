param(
    [string]$BaseUrl = "http://localhost:8087",
    [string]$Token = $env:CHECKIN_STAFF_JWT,
    [string]$ConcertId = "concert-1",
    [string]$EvidencePath = ""
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $EvidencePath = Join-Path $PSScriptRoot "../evidence/e-ticket-service/api-test.log"
}
if ([string]::IsNullOrWhiteSpace($Token)) {
    $Token = $env:STAFF_JWT
}

function Write-Evidence {
    param([string]$Message)
    $line = "[{0}] {1}" -f (Get-Date -Format "s"), $Message
    Write-Host $line
    Add-Content -Path $EvidencePath -Value $line
}

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Path,
        [hashtable]$Headers = @{},
        [object]$Body = $null
    )

    $params = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        Headers = $Headers
        ContentType = "application/json"
    }
    if ($null -ne $Body) {
        $params.Body = ($Body | ConvertTo-Json -Depth 20)
    }
    Invoke-RestMethod @params
}

New-Item -ItemType Directory -Force -Path (Split-Path $EvidencePath) | Out-Null
Set-Content -Path $EvidencePath -Value "# e-ticket-service API test"

Write-Evidence "BaseUrl=$BaseUrl"

$health = Invoke-Json -Method GET -Path "/health"
Write-Evidence "health response=$($health | ConvertTo-Json -Compress)"

$rejected = $false
try {
    Invoke-Json -Method GET -Path "/api/tickets" | Out-Null
} catch {
    $statusCode = [int]$_.Exception.Response.StatusCode
    if ($statusCode -eq 401 -or $statusCode -eq 403) {
        $rejected = $true
        Write-Evidence "unauthenticated /api/tickets rejected status=$statusCode"
    } else {
        throw
    }
}
if (-not $rejected) {
    throw "Expected unauthenticated /api/tickets to fail"
}

if ([string]::IsNullOrWhiteSpace($Token)) {
    Write-Evidence "CHECKIN_STAFF_JWT/STAFF_JWT/Token not provided; authenticated checks skipped"
    exit 0
}

$headers = @{ Authorization = "Bearer $Token" }
$snapshot = Invoke-Json -Method GET -Path "/internal/tickets/snapshot?concertId=$ConcertId" -Headers $headers
Write-Evidence "snapshot success=$($snapshot.success) totalCount=$($snapshot.data.totalCount)"

Write-Evidence "PASS"
