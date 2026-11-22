param(
 [Parameter(Mandatory=$true)][string]$Model,
 [string]$LlamaServer=(Join-Path $PSScriptRoot 'llama-server.exe'),
 [Parameter(Mandatory=$true)][string[]]$Serial,
 [ValidateRange(512,32768)][int]$Context=1024,
 [ValidateRange(1,65535)][int]$ApiPort=8081,
 [ValidateRange(0,999)][int]$OffloadLayers=99,
 [string]$Adb='adb'
)
$ErrorActionPreference='Stop'
$Model=(Resolve-Path -LiteralPath $Model).Path
$LlamaServer=(Resolve-Path -LiteralPath $LlamaServer).Path
if ([IO.Path]::GetExtension($Model) -ne '.gguf') { throw 'Use a GGUF model.' }
$forwarded=@(); $targets=@()
try {
 foreach($device in ($Serial | Select-Object -Unique)) {
  if ($device -notmatch '^[a-zA-Z0-9_.:\-\[\]]+$') { throw "Invalid ADB serial: $device" }
  $state=& $Adb -s $device get-state
  if ($LASTEXITCODE -ne 0 -or "$state".Trim() -ne 'device') { throw "ADB node unavailable: $device" }
  $raw=& $Adb -s $device forward tcp:0 tcp:50052
  if ($LASTEXITCODE -ne 0 -or "$raw".Trim() -notmatch '^\d+$') { throw "Cannot forward $device" }
  $port="$raw".Trim()
  $forwarded+=@(@{serial=$device;port=$port}); $targets+="127.0.0.1:$port"
 }
 Write-Host "RPC nodes: $($targets -join ',')"
 Write-Host "CyberTiel OpenAI-compatible API: http://127.0.0.1:$ApiPort/v1"
 & $LlamaServer -m $Model --rpc ($targets -join ',') -ngl $OffloadLayers --ctx-size $Context --parallel 1 --host 127.0.0.1 --port $ApiPort --alias sharedcompute
 if ($LASTEXITCODE -ne 0) { throw "llama-server failed ($LASTEXITCODE). See logs, check model compatibility and RAM." }
} finally {
 foreach($f in $forwarded) { & $Adb -s $f.serial forward --remove "tcp:$($f.port)" | Out-Null }
}
