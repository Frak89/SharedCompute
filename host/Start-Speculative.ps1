# Native llama.cpp speculative decoding, without duplicate GGUFs.
param(
 [Parameter(Mandatory=$true)][string]$Model,
 [string]$Draft,
 [switch]$MTP,
 [string]$Server=(Join-Path $PSScriptRoot 'llama-server.exe'),
 [ValidateRange(512,32768)][int]$Context=1024,
 [ValidateRange(1,65535)][int]$Port=9931,
 [ValidateRange(1,16)][int]$Predict=4
)
$ErrorActionPreference='Stop'
$Server=(Resolve-Path -LiteralPath $Server).Path
$Model=(Resolve-Path -LiteralPath $Model).Path
if ([bool]$MTP -eq [bool]$Draft) { throw 'Choose exactly one: -Draft compatible.gguf or -MTP.' }
$help=(& $Server --help 2>&1 | Out-String)
if ($help -notmatch '--spec-type' -or $help -notmatch '--spec-draft-n-max') { throw 'This llama-server version lacks the verified speculative flags.' }
$argsList=@('-m',$Model,'--host','127.0.0.1','--port',"$Port",'--alias','crosscompute-spec','--ctx-size',"$Context",'--parallel','1','--spec-draft-n-max',"$Predict")
if ($MTP) {
 $argsList+=@('--spec-type','draft-mtp')
} else {
 $Draft=(Resolve-Path -LiteralPath $Draft).Path
 if ($Draft -eq $Model) { throw 'Choose a separate, small compatible draft GGUF.' }
 $argsList+=@('--spec-type','draft-simple','--spec-draft-model',$Draft)
}
Write-Host 'Speculative decoding requires compatible architecture/tokenizer and enough RAM. No model files are copied.'
& $Server @argsList
if ($LASTEXITCODE -ne 0) { throw "llama-server failed: $LASTEXITCODE" }
