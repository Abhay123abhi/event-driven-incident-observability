[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [Parameter(Mandatory)]
    [ValidateRange(0, 2147483647)]
    [int]$Partition,
    [Parameter(Mandatory)]
    [ValidateRange(0, 9223372036854775807)]
    [long]$Offset,
    [switch]$ConfirmReplay
)

$ErrorActionPreference = 'Stop'
$DeadLetterTopic = 'incident-investigation.DLT'
$destinationTopic = 'incident-investigation'

# Read exactly one selected record. This does not consume an entire DLT or change its offsets.
$consumerArgs = @('compose','exec','-T','broker','kafka-console-consumer','--bootstrap-server','broker:29092','--topic',$DeadLetterTopic,'--partition',[string]$Partition,'--offset',[string]$Offset,'--max-messages','1','--timeout-ms','10000','--property','print.key=true','--property','key.separator=|')
$recordLines = & docker @consumerArgs 2>&1
if ($LASTEXITCODE -ne 0) { throw "Kafka could not read the selected DLT record: $($recordLines -join ' ')" }
$line = $recordLines | Where-Object { [string]$_ -match '^([^|]+)\|(.+)$' } | Select-Object -First 1
if (-not $line) { throw 'No keyed record was read at that partition/offset, or the payload was empty. No replay performed.' }
$match = [regex]::Match([string]$line, '^([^|]+)\|(.+)$')
$key = $match.Groups[1].Value
$payload = $match.Groups[2].Value
try { $event = $payload | ConvertFrom-Json -ErrorAction Stop } catch { throw 'DLT payload is not valid JSON. No replay performed.' }
if ([string]::IsNullOrWhiteSpace([string]$event.incidentId) -or $key -ne [string]$event.incidentId) { throw 'Event key does not match payload incidentId. No replay performed.' }
if ($event.PSObject.Properties.Name -notcontains 'alert') { throw 'Investigation event is missing its alert payload. No replay performed.' }
if ([string]$event.incidentId -notmatch '^[a-zA-Z0-9-]{1,36}$') { throw 'Unexpected incident ID format. No replay performed.' }

# A replay is allowed only for a known failed investigation; do not duplicate successful work or notifications.
$sql = "SELECT status FROM incidents WHERE id = '$($event.incidentId)';"
$dbArgs = @('compose','exec','-T','postgres','psql','-U','observe','-d','incident_platform','-Atc',$sql)
$statusOutput = & docker @dbArgs 2>&1
if ($LASTEXITCODE -ne 0) { throw "Could not verify incident state in PostgreSQL: $($statusOutput -join ' ')" }
$currentStatus = ($statusOutput | Out-String).Trim()
if ($currentStatus -ne 'INVESTIGATION_FAILED') { throw "Replay is allowed only for INVESTIGATION_FAILED; current status is '$currentStatus'. No replay performed." }

Write-Host "Selected DLT record: $DeadLetterTopic partition $Partition offset $Offset"
Write-Host "Incident: $($event.incidentId); status: $currentStatus; destination: $destinationTopic"
if (-not $ConfirmReplay) { Write-Host 'DRY RUN only. No Kafka message was produced. Review this event, then rerun with -ConfirmReplay to replay this exact record.'; return }

$producerInput = "$key|$payload"
$producerArgs = @('compose','exec','-T','broker','kafka-console-producer','--bootstrap-server','broker:29092','--topic',$destinationTopic,'--property','parse.key=true','--property','key.separator=|')
$producerInput | & docker @producerArgs 2>&1
if ($LASTEXITCODE -ne 0) { throw "Kafka producer failed while replaying to $destinationTopic." }
Write-Host 'Replay submitted. Verify incident-service logs and report before attempting any further replay.'
