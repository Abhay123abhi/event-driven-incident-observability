param(
    [ValidateSet('latency', 'errors', 'downstream')]
    [string]$Scenario = 'latency'
)
$ErrorActionPreference = 'Stop'
$inventory = 'http://127.0.0.1:8082'
# Fail early if demo profile is missing; do not mistake a 404 for an injected error.
Invoke-RestMethod -Method Post -Uri "$inventory/demo/failures?latencyMillis=0&failRequests=false" | Out-Null
$body = @{ skuCode = 'keyboard-001'; price = 100; quantity = 1 } | ConvertTo-Json
try {
    if ($Scenario -eq 'latency') {
        Invoke-RestMethod -Method Post -Uri "$inventory/demo/failures?latencyMillis=3000&failRequests=false" | Out-Null
    } else {
        Invoke-RestMethod -Method Post -Uri "$inventory/demo/failures?latencyMillis=0&failRequests=true" | Out-Null
    }
    $deadline = [DateTime]::UtcNow.AddMinutes(5)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            if ($Scenario -eq 'downstream') {
                Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:9000/api/order' -ContentType 'application/json' -Body $body -TimeoutSec 15 | Out-Null
            } else {
                Invoke-RestMethod -Uri "$inventory/api/inventory?skuCode=keyboard-001&quantity=1" -TimeoutSec 15 | Out-Null
            }
        } catch {
            if ($Scenario -eq 'latency') { throw }
            if (-not $_.Exception.Response) { throw }
            $status = [int]$_.Exception.Response.StatusCode
            if ($status -lt 500) { throw }
            Write-Host "Observed HTTP $status during $Scenario test"
        }
        if ($Scenario -ne 'latency') { Start-Sleep -Seconds 2 }
    }
} finally {
    Invoke-RestMethod -Method Delete -Uri "$inventory/demo/failures" | Out-Null
    Write-Host 'Failure injection reset. Allow the metric window and alert delivery delay to clear.'
}
