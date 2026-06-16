param(
    [string]$ETicketBaseUrl = "http://localhost:8087",
    [string]$CheckinBaseUrl = "http://localhost:8088",
    [string]$Network = "local_tickefy-network",
    [string]$JwtSecret = "dev-only-secret-minimum-32-chars-long",
    [string]$EvidencePath = "",
    [switch]$SkipContainerStart,
    [switch]$KeepContainers
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $EvidencePath = Join-Path $PSScriptRoot "../evidence/backend-services-real-api/real-api-db-test.log"
}

$EvidenceDir = Split-Path $EvidencePath
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
Set-Content -Path $EvidencePath -Value "# Backend services real API + real DB test"

$RunId = "realapi-" + (Get-Date -Format "yyyyMMddHHmmss")
$ConcertA = "$RunId-concert-a"
$ConcertB = "$RunId-concert-b"
$UserA = "$RunId-user-a"
$UserB = "$RunId-user-b"
$Staff = "$RunId-staff"
$SpoofStaff = "$RunId-spoof-staff"

$ETicketContainer = "tickefy-eticket-realapi"
$CheckinContainer = "tickefy-checkin-realapi"

function Write-Evidence {
    param([string]$Message)
    $line = "[{0}] {1}" -f (Get-Date -Format "s"), $Message
    Write-Host $line
    Add-Content -Path $EvidencePath -Value $line
}

function ConvertTo-Base64Url {
    param([byte[]]$Bytes)
    return [Convert]::ToBase64String($Bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function New-TestJwt {
    param(
        [string]$Subject,
        [string[]]$Roles
    )
    $header = @{ alg = "HS256"; typ = "JWT" } | ConvertTo-Json -Compress
    $now = [DateTimeOffset]::UtcNow
    $payload = @{
        sub = $Subject
        roles = $Roles
        iat = [int64]$now.ToUnixTimeSeconds()
        exp = [int64]$now.AddHours(2).ToUnixTimeSeconds()
    } | ConvertTo-Json -Compress
    $headerEncoded = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($header))
    $payloadEncoded = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($payload))
    $toSign = "$headerEncoded.$payloadEncoded"
    $hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($JwtSecret))
    $signature = ConvertTo-Base64Url ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($toSign)))
    return "$toSign.$signature"
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Url,
        [hashtable]$Headers = @{},
        [object]$Body = $null,
        [int]$ExpectedStatus = 200,
        [string]$Name
    )
    $params = @{
        Method = $Method
        Uri = $Url
        Headers = $Headers
        ContentType = "application/json"
        SkipHttpErrorCheck = $true
    }
    if ($null -ne $Body) {
        $params.Body = ($Body | ConvertTo-Json -Depth 20)
    }
    $response = Invoke-WebRequest @params
    $status = [int]$response.StatusCode
    $content = $response.Content
    Write-Evidence "$Name status=$status"
    if (-not [string]::IsNullOrWhiteSpace($content)) {
        Write-Evidence "$Name response=$content"
    }
    if ($status -ne $ExpectedStatus) {
        throw "$Name expected status $ExpectedStatus but got $status"
    }
    if ([string]::IsNullOrWhiteSpace($content)) {
        return $null
    }
    return $content | ConvertFrom-Json
}

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )
    if (-not $Condition) {
        throw "ASSERT FAILED: $Message"
    }
    Write-Evidence "ASSERT PASS: $Message"
}

function Invoke-Sql {
    param([string]$Sql)
    $result = docker exec tickefy-postgres psql -U tickefy -d tickefy -t -A -v ON_ERROR_STOP=1 -c $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "SQL failed: $Sql"
    }
    return ($result | Out-String).Trim()
}

function Wait-Health {
    param(
        [string]$BaseUrl,
        [string]$Name
    )
    for ($i = 0; $i -lt 60; $i++) {
        try {
            $response = Invoke-WebRequest -Method GET -Uri "$BaseUrl/health" -SkipHttpErrorCheck
            if ([int]$response.StatusCode -eq 200) {
                Write-Evidence "$Name health ready"
                return
            }
        } catch {
            Start-Sleep -Seconds 1
        }
        Start-Sleep -Seconds 1
    }
    throw "$Name health not ready"
}

function Start-ServiceContainers {
    docker rm -f $ETicketContainer $CheckinContainer 2>$null | Out-Null

    docker run -d --name $ETicketContainer --network $Network -p 8087:8087 `
        -e SERVER_PORT=8087 `
        -e DB_HOST=tickefy-postgres `
        -e DB_PORT=5432 `
        -e DB_NAME=tickefy `
        -e DB_USERNAME=tickefy `
        -e DB_PASSWORD=change_me `
        -e DB_SCHEMA=ticket_schema `
        -e JWT_SECRET=$JwtSecret `
        -e LOG_LEVEL=INFO `
        tickefy/e-ticket-service:local | Out-Null

    docker run -d --name $CheckinContainer --network $Network -p 8088:8088 `
        -e SERVER_PORT=8088 `
        -e DB_HOST=tickefy-postgres `
        -e DB_PORT=5432 `
        -e DB_NAME=tickefy `
        -e DB_USERNAME=tickefy `
        -e DB_PASSWORD=change_me `
        -e DB_SCHEMA=checkin_schema `
        -e JWT_SECRET=$JwtSecret `
        -e ETICKET_SERVICE_URL=http://$ETicketContainer`:8087 `
        -e LOG_LEVEL=INFO `
        tickefy/checkin-service:local | Out-Null

    Wait-Health -BaseUrl $ETicketBaseUrl -Name "e-ticket"
    Wait-Health -BaseUrl $CheckinBaseUrl -Name "checkin"
}

function Stop-ServiceContainers {
    if ($KeepContainers) {
        Write-Evidence "containers kept: $ETicketContainer, $CheckinContainer"
        return
    }
    docker logs $ETicketContainer *> (Join-Path $EvidenceDir "e-ticket-container.log") 2>$null
    docker logs $CheckinContainer *> (Join-Path $EvidenceDir "checkin-container.log") 2>$null
    docker rm -f $ETicketContainer $CheckinContainer 2>$null | Out-Null
}

function New-IssueBody {
    param(
        [string]$OrderItem,
        [string]$User,
        [string]$Concert
    )
    return @{
        orderId = "$RunId-order"
        orderItemId = $OrderItem
        userId = $User
        eventId = $Concert
        ticketTypeId = "type-ga"
        zoneId = "GA"
        ticketName = "General Admission"
    }
}

function Issue-Ticket {
    param(
        [string]$OrderItem,
        [string]$User,
        [string]$Concert,
        [string]$Name
    )
    $response = Invoke-Api -Method POST -Url "$ETicketBaseUrl/internal/tickets/issue" `
        -Headers $OrganizerHeaders -Body (New-IssueBody -OrderItem $OrderItem -User $User -Concert $Concert) `
        -ExpectedStatus 201 -Name $Name
    Assert-True ($response.success -eq $true) "$Name success envelope"
    Assert-True (-not [string]::IsNullOrWhiteSpace($response.data.qrToken)) "$Name qrToken returned"
    return $response.data
}

$OrganizerToken = New-TestJwt -Subject "$RunId-organizer" -Roles @("ORGANIZER")
$StaffToken = New-TestJwt -Subject $Staff -Roles @("CHECKIN_STAFF")
$CustomerAToken = New-TestJwt -Subject $UserA -Roles @("CUSTOMER")
$CustomerBToken = New-TestJwt -Subject $UserB -Roles @("CUSTOMER")

$OrganizerHeaders = @{ Authorization = "Bearer $OrganizerToken" }
$StaffHeaders = @{ Authorization = "Bearer $StaffToken" }
$CustomerAHeaders = @{ Authorization = "Bearer $CustomerAToken" }
$CustomerBHeaders = @{ Authorization = "Bearer $CustomerBToken" }

try {
    Write-Evidence "runId=$RunId"
    Write-Evidence "realDb=postgres container tickefy-postgres database=tickefy schemas=ticket_schema,checkin_schema"

    Invoke-Sql "DELETE FROM checkin_schema.checkin_events WHERE concert_id LIKE '$RunId%';"
    Invoke-Sql "DELETE FROM checkin_schema.sync_batches WHERE sync_batch_id LIKE '$RunId%';"
    Invoke-Sql "DELETE FROM checkin_schema.conflicts WHERE concert_id LIKE '$RunId%';"
    Invoke-Sql "DELETE FROM ticket_schema.tickets WHERE order_id LIKE '$RunId%';"

    if (-not $SkipContainerStart) {
        Start-ServiceContainers
    } else {
        Wait-Health -BaseUrl $ETicketBaseUrl -Name "e-ticket"
        Wait-Health -BaseUrl $CheckinBaseUrl -Name "checkin"
    }

    Invoke-Api -Method GET -Url "$ETicketBaseUrl/api/tickets" -ExpectedStatus 401 -Name "ETICKET unauth customer tickets" | Out-Null
    Invoke-Api -Method GET -Url "$CheckinBaseUrl/api/checkin/events/$ConcertA" -ExpectedStatus 401 -Name "CHECKIN unauth history" | Out-Null
    Invoke-Api -Method GET -Url "$CheckinBaseUrl/api/checkin/events/$ConcertA" -Headers $CustomerAHeaders -ExpectedStatus 403 -Name "CHECKIN customer forbidden history" | Out-Null

    $ticketList = @()
    $ticketAccepted = Issue-Ticket -OrderItem "$RunId-item-accepted" -User $UserA -Concert $ConcertA -Name "ETICKET issue accepted ticket"
    $ticketDuplicate = Invoke-Api -Method POST -Url "$ETicketBaseUrl/internal/tickets/issue" `
        -Headers $OrganizerHeaders -Body (New-IssueBody -OrderItem "$RunId-item-accepted" -User $UserA -Concert $ConcertA) `
        -ExpectedStatus 201 -Name "ETICKET duplicate issue same orderItemId"
    Assert-True ($ticketDuplicate.data.id -eq $ticketAccepted.id) "duplicate issue returns same ticket id"
    Assert-True ($ticketDuplicate.data.qrToken -eq $ticketAccepted.qrToken) "duplicate issue returns same qrToken"

    $ticketWrongEvent = Issue-Ticket -OrderItem "$RunId-item-wrong-event" -User $UserA -Concert $ConcertB -Name "ETICKET issue wrong-event ticket"
    $ticketCancelled = Issue-Ticket -OrderItem "$RunId-item-cancelled" -User $UserA -Concert $ConcertA -Name "ETICKET issue cancelled ticket"
    $ticketRefunded = Issue-Ticket -OrderItem "$RunId-item-refunded" -User $UserA -Concert $ConcertA -Name "ETICKET issue refunded ticket"
    $ticketSpoof = Issue-Ticket -OrderItem "$RunId-item-spoof" -User $UserA -Concert $ConcertA -Name "ETICKET issue spoof scan ticket"
    $ticketSync = Issue-Ticket -OrderItem "$RunId-item-sync" -User $UserA -Concert $ConcertA -Name "ETICKET issue sync conflict ticket"

    Invoke-Sql "UPDATE ticket_schema.tickets SET status='CANCELLED' WHERE id='$($ticketCancelled.id)';"
    Invoke-Sql "UPDATE ticket_schema.tickets SET status='REFUNDED' WHERE id='$($ticketRefunded.id)';"

    $customerTickets = Invoke-Api -Method GET -Url "$ETicketBaseUrl/api/tickets" `
        -Headers ($CustomerAHeaders + @{ "X-User-Id" = $UserB }) -ExpectedStatus 200 -Name "ETICKET customer list ignores spoof header"
    Assert-True ($customerTickets.success -eq $true) "customer list success"
    Assert-True (($customerTickets.data | Where-Object { $_.userId -ne $UserA }).Count -eq 0) "customer list only JWT subject user"

    Invoke-Api -Method GET -Url "$ETicketBaseUrl/api/tickets/$($ticketAccepted.id)" `
        -Headers $CustomerBHeaders -ExpectedStatus 403 -Name "ETICKET other user ticket forbidden" | Out-Null
    Invoke-Api -Method GET -Url "$ETicketBaseUrl/internal/tickets/snapshot?concertId=$ConcertA" `
        -Headers $CustomerAHeaders -ExpectedStatus 403 -Name "ETICKET customer forbidden internal snapshot" | Out-Null
    $eticketSnapshot = Invoke-Api -Method GET -Url "$ETicketBaseUrl/internal/tickets/snapshot?concertId=$ConcertA" `
        -Headers $StaffHeaders -ExpectedStatus 200 -Name "ETICKET staff internal snapshot"
    Assert-True ($eticketSnapshot.data.tickets.Count -ge 3) "e-ticket snapshot returns issued tickets only"

    $checkinSnapshot = Invoke-Api -Method GET -Url "$CheckinBaseUrl/api/checkin/snapshot/${ConcertA}?gate=gate-A" `
        -Headers $StaffHeaders -ExpectedStatus 200 -Name "CHECKIN real snapshot"
    Assert-True ($checkinSnapshot.success -eq $true) "checkin snapshot success"
    Assert-True ($null -ne $checkinSnapshot.data.tickets) "checkin snapshot has tickets field"
    Assert-True ($null -eq $checkinSnapshot.data.tokens) "checkin snapshot has no tokens field"

    $scanAccepted = Invoke-Api -Method POST -Url "$CheckinBaseUrl/api/checkin/scan" -Headers $StaffHeaders `
        -Body @{ qrToken = $ticketAccepted.qrToken; concertId = $ConcertA; deviceId = "$RunId-device"; gate = "gate-A" } `
        -ExpectedStatus 200 -Name "CHECKIN scan valid accepted"
    Assert-True ($scanAccepted.data.result -eq "ACCEPTED") "valid scan ACCEPTED"

    $scanDuplicate = Invoke-Api -Method POST -Url "$CheckinBaseUrl/api/checkin/scan" -Headers $StaffHeaders `
        -Body @{ qrToken = $ticketAccepted.qrToken; concertId = $ConcertA; deviceId = "$RunId-device"; gate = "gate-A" } `
        -ExpectedStatus 200 -Name "CHECKIN scan duplicate"
    Assert-True ($scanDuplicate.data.result -eq "DUPLICATE_REJECTED") "duplicate scan DUPLICATE_REJECTED"

    $scanInvalid = Invoke-Api -Method POST -Url "$CheckinBaseUrl/api/checkin/scan" -Headers $StaffHeaders `
        -Body @{ qrToken = "$RunId-invalid-token"; concertId = $ConcertA; deviceId = "$RunId-device"; gate = "gate-A" } `
        -ExpectedStatus 200 -Name "CHECKIN scan invalid QR"
    Assert-True ($scanInvalid.data.result -eq "INVALID_QR_TOKEN") "invalid QR maps INVALID_QR_TOKEN"

    $scanWrong = Invoke-Api -Method POST -Url "$CheckinBaseUrl/api/checkin/scan" -Headers $StaffHeaders `
        -Body @{ qrToken = $ticketWrongEvent.qrToken; concertId = $ConcertA; deviceId = "$RunId-device"; gate = "gate-A" } `
        -ExpectedStatus 200 -Name "CHECKIN scan wrong event"
    Assert-True ($scanWrong.data.result -eq "WRONG_EVENT") "wrong event maps WRONG_EVENT"

    $scanCancelled = Invoke-Api -Method POST -Url "$CheckinBaseUrl/api/checkin/scan" -Headers $StaffHeaders `
        -Body @{ qrToken = $ticketCancelled.qrToken; concertId = $ConcertA; deviceId = "$RunId-device"; gate = "gate-A" } `
        -ExpectedStatus 200 -Name "CHECKIN scan cancelled"
    Assert-True ($scanCancelled.data.result -eq "CANCELLED_TICKET") "cancelled maps CANCELLED_TICKET"

    $scanRefunded = Invoke-Api -Method POST -Url "$CheckinBaseUrl/api/checkin/scan" -Headers $StaffHeaders `
        -Body @{ qrToken = $ticketRefunded.qrToken; concertId = $ConcertA; deviceId = "$RunId-device"; gate = "gate-A" } `
        -ExpectedStatus 200 -Name "CHECKIN scan refunded"
    Assert-True ($scanRefunded.data.result -eq "REFUNDED_TICKET") "refunded maps REFUNDED_TICKET"

    $spoofHeaders = $StaffHeaders + @{ "X-User-Id" = $SpoofStaff }
    $scanSpoof = Invoke-Api -Method POST -Url "$CheckinBaseUrl/api/checkin/scan" -Headers $spoofHeaders `
        -Body @{ qrToken = $ticketSpoof.qrToken; concertId = $ConcertA; deviceId = "$RunId-device"; gate = "gate-A" } `
        -ExpectedStatus 200 -Name "CHECKIN scan spoof header ignored"
    Assert-True ($scanSpoof.data.result -eq "ACCEPTED") "spoof scan accepted"
    $storedStaff = Invoke-Sql "SELECT staff_id FROM checkin_schema.checkin_events WHERE ticket_id='$($ticketSpoof.id)' ORDER BY created_at DESC LIMIT 1;"
    Assert-True ($storedStaff -eq $Staff) "stored staff_id comes from JWT, not X-User-Id"

    $syncBody1 = @{
        syncBatchId = "$RunId-batch-1"
        deviceId = "$RunId-device-1"
        concertId = $ConcertA
        gate = "gate-A"
        items = @(@{ localId = "$RunId-local-1"; qrToken = $ticketSync.qrToken; localResult = "OFFLINE_ACCEPTED"; scannedAt = (Get-Date).ToUniversalTime().ToString("o") })
    }
    $sync1 = Invoke-Api -Method POST -Url "$CheckinBaseUrl/api/checkin/sync" -Headers $StaffHeaders `
        -Body $syncBody1 -ExpectedStatus 200 -Name "CHECKIN sync first device wins"
    Assert-True ($sync1.data.accepted.Count -eq 1) "first sync accepted"
    Assert-True ($sync1.data.conflicts.Count -eq 0) "first sync no conflict"

    $syncBody2 = @{
        syncBatchId = "$RunId-batch-2"
        deviceId = "$RunId-device-2"
        concertId = $ConcertA
        gate = "gate-B"
        items = @(@{ localId = "$RunId-local-2"; qrToken = $ticketSync.qrToken; localResult = "OFFLINE_ACCEPTED"; scannedAt = (Get-Date).ToUniversalTime().ToString("o") })
    }
    $sync2 = Invoke-Api -Method POST -Url "$CheckinBaseUrl/api/checkin/sync" -Headers $StaffHeaders `
        -Body $syncBody2 -ExpectedStatus 200 -Name "CHECKIN sync second device conflict"
    Assert-True ($sync2.data.accepted.Count -eq 0) "second sync no accepted"
    Assert-True ($sync2.data.conflicts.Count -eq 1) "second sync conflict"
    Assert-True ($sync2.data.conflicts[0].serverResult -eq "DUPLICATE_REJECTED") "second sync duplicate conflict result"

    $syncReplay = Invoke-Api -Method POST -Url "$CheckinBaseUrl/api/checkin/sync" -Headers $StaffHeaders `
        -Body $syncBody1 -ExpectedStatus 200 -Name "CHECKIN sync idempotent replay"
    Assert-True ($syncReplay.data.accepted.Count -eq 1) "sync replay returns cached accepted"
    $batchCount = [int](Invoke-Sql "SELECT COUNT(*) FROM checkin_schema.sync_batches WHERE sync_batch_id='$RunId-batch-1';")
    Assert-True ($batchCount -eq 1) "sync replay did not create duplicate batch"

    $history = Invoke-Api -Method GET -Url "$CheckinBaseUrl/api/checkin/events/${ConcertA}?page=0&size=50" `
        -Headers $StaffHeaders -ExpectedStatus 200 -Name "CHECKIN history"
    Assert-True ($history.success -eq $true) "history success"
    Assert-True ($history.data.content.Count -ge 8) "history contains scan and sync events"

    $ticketCount = [int](Invoke-Sql "SELECT COUNT(*) FROM ticket_schema.tickets WHERE order_id='$RunId-order';")
    $eventCount = [int](Invoke-Sql "SELECT COUNT(*) FROM checkin_schema.checkin_events WHERE concert_id IN ('$ConcertA', '$ConcertB');")
    $syncBatchCount = [int](Invoke-Sql "SELECT COUNT(*) FROM checkin_schema.sync_batches WHERE sync_batch_id LIKE '$RunId%';")
    Write-Evidence "DB ticketCount=$ticketCount eventCount=$eventCount syncBatchCount=$syncBatchCount"
    Assert-True ($ticketCount -ge 6) "real DB contains issued test tickets"
    Assert-True ($eventCount -ge 8) "real DB contains checkin audit events"
    Assert-True ($syncBatchCount -eq 2) "real DB contains exactly two sync batches"

    Write-Evidence "PASS"
} finally {
    if (-not $SkipContainerStart) {
        Stop-ServiceContainers
    }
}
