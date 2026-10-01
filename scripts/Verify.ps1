param([switch]$Install, [ValidateSet('universal', 'arm64-v8a', 'armeabi-v7a', 'x86')][string]$Abi = 'universal', [switch]$PlanOnly)
# Compatibility entry point; the implementation lives with the other verifiers.
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'verification\Verify.ps1') @PSBoundParameters
