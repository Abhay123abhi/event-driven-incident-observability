param(
    [string]$BaseUrl = 'http://localhost:8084',
    [int]$TimeoutSeconds = 120
)
$ErrorActionPreference = 'Stop'
$fingerprint = 'smoke-' + [guid]::NewGuid().ToString('N')
$startedAt = [DateTime]::UtcNow.ToString('o')
$incidentId = $null

function Send-Alert([string]$Status) {
    $alert = @{
        status = $Status
        fingerprint = $fingerprint
        startsAt = $startedAt
        labels = @{ service = 'inventory-service'; alertname = 'SmokeTest'; severity = 'warning' }
        annotations = @{ summary = 'Local incident workflow smoke test' }
    }
    if ($Status -eq 'resolved') { $alert.endsAt = [DateTime]::UtcNow.ToString('o') }
    $body = @{ status = $Status; alerts = @($alert) } | ConvertTo-Json -Depth 6
    return Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/incidents/webhooks/alertmanager" `
        -ContentType 'application/json' -Body $body -TimeoutSec 20
}

function Wait-ForStatus([string]$Expected) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $page = Invoke-RestMethod -Uri "$BaseUrl/api/incidents?scope=all&size=100" -TimeoutSec 20
        $incident = $page.items | Where-Object { $_.incidentId -eq $incidentId } | Select-Object -First 1
        if ($incident.status -eq $Expected) { return $incident }
        if ($incident.status -eq 'INVESTIGATION_FAILED' -and $Expected -eq 'INVESTIGATED') {
            throw 'Investigation failed. Check docker compose logs incident-service.'
        }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for incident $incidentId to reach $Expected."
}

try {
    $accepted = @(Send-Alert 'firing')
    $incidentId = $accepted[0].incidentId
    if (-not $incidentId) { throw 'Webhook did not return an incident ID.' }
    $duplicate = @(Send-Alert 'firing')
    if ($duplicate[0].incidentId -ne $incidentId) { throw 'Repeated firing alert created a different incident.' }
    $report = Wait-ForStatus 'INVESTIGATED'
    if (@($report.evidence).Count -eq 0) { throw 'Report contains neither evidence nor collection notes.' }
    Send-Alert 'resolved' | Out-Null
    $resolved = Wait-ForStatus 'RESOLVED'
    $repeated = @(Send-Alert 'resolved')
    if ($repeated[0].incidentId -ne $incidentId) { throw 'Repeated resolution changed the incident ID.' }
    Write-Host "PASS: $incidentId was investigated, deduplicated, and resolved."
    $report.evidence | ForEach-Object { Write-Host "  $_" }
} finally {
    if ($incidentId) {
        try { Send-Alert 'resolved' | Out-Null }
        catch { Write-Warning "Could not clean up smoke incident $incidentId. Check incident-service logs." }
    }
}
