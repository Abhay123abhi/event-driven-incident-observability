param(
    [string]$BaseUrl = 'http://127.0.0.1:8084',
    [string]$AlertName = 'HighErrorRate',
    [string]$ServiceName = 'inventory-service',
    [int]$LookbackMinutes = 30,
    [switch]$RequireResolved
)
$ErrorActionPreference = 'Stop'

# Read-only verification: run after the real failure scenario. The smoke test
# checks direct webhook processing; this checks an incident was produced by
# the actual Prometheus -> Alertmanager path during the selected time window.
$cutoff = [DateTime]::UtcNow.AddMinutes(-$LookbackMinutes)
$page = 0
$size = 100
$match = $null

do {
    $uri = "$BaseUrl/api/incidents?scope=all&page=$page&size=$size"
    $response = Invoke-RestMethod -Uri $uri -TimeoutSec 20
    $match = @($response.items) | Where-Object {
        $_.alertName -eq $AlertName -and
        $_.service -eq $ServiceName -and
        ([DateTime]$_.detectedAt).ToUniversalTime() -ge $cutoff
    } | Select-Object -First 1
    $page++
} while (-not $match -and $page -lt [int]$response.totalPages)

if (-not $match) {
    throw "FAIL: No recent $AlertName incident for $ServiceName. Check Prometheus alerts and Alertmanager webhook delivery."
}

$incident = Invoke-RestMethod -Uri "$BaseUrl/api/incidents/$($match.incidentId)" -TimeoutSec 20
if (-not $incident.investigatedAt) {
    throw "FAIL: Incident $($incident.incidentId) exists but investigation has not completed (status=$($incident.status))."
}
if (@($incident.evidence).Count -eq 0) {
    throw "FAIL: Incident $($incident.incidentId) has no evidence or collection notes."
}
if ($RequireResolved -and $incident.status -ne 'RESOLVED') {
    throw "FAIL: Incident $($incident.incidentId) is not yet resolved (status=$($incident.status))."
}
if (@($incident.evidence | Where-Object { $_ -match 'QUERY_FAILED|NO_DATA|unavailable' }).Count -gt 0) {
    Write-Warning "Evidence includes unavailable or missing telemetry; investigate individual sources."
}

Write-Host "PASS: Observed $AlertName incident $($incident.incidentId) for $ServiceName."
Write-Host "  Status: $($incident.status); investigatedAt: $($incident.investigatedAt); evidence lines: $(@($incident.evidence).Count)"
Write-Host '  Note: this verifies the incident API result; inspect outbox rows to validate broker publication.'
