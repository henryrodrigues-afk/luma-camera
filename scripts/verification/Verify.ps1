param([switch]$Install, [ValidateSet('universal', 'arm64-v8a', 'armeabi-v7a', 'x86')][string]$Abi = 'universal', [switch]$PlanOnly)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$gradleSource = Get-Content -LiteralPath (Join-Path $projectDirectory 'app\build.gradle.kts') -Raw
$versionMatches = [regex]::Matches($gradleSource, '(?m)^\s*versionName\s*=\s*"([^"]+)"')
if ($versionMatches.Count -ne 1) { throw 'Expected one literal versionName in app/build.gradle.kts.' }
$versionName = $versionMatches[0].Groups[1].Value
$releaseMatch = [regex]::Match($versionName, '^(\d+)\.(\d+)(?:\.\d+)?(?:[-+][A-Za-z0-9.-]+)?$')
if (-not $releaseMatch.Success) { throw 'versionName must start with a numeric major.minor version.' }
$releaseVersion = $releaseMatch.Groups[1].Value + '.' + $releaseMatch.Groups[2].Value
$apkDirectory = Join-Path $projectDirectory "dist\releases\$releaseVersion\apk"
$reportDirectory = Join-Path $projectDirectory "dist\reports\$releaseVersion"
$testDirectory = Join-Path $reportDirectory 'tests'
$rawReportDirectory = Join-Path $reportDirectory 'raw'
$apkName = if ($Abi -eq 'universal') { "LumaCamera-$releaseVersion-debug.apk" } else { "LumaCamera-$releaseVersion-$Abi-debug.apk" }
$apkPath = Join-Path $apkDirectory $apkName
$buildLog = Join-Path $reportDirectory "build-verification-$releaseVersion.log"
# ASCII-only output path avoids Java 17/Gradle worker classpath issues on Windows.
$buildRoot = Join-Path $env:LOCALAPPDATA 'LumaCamera\verification'
$gradleArguments = @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', "-PlumaBuildRoot=$($buildRoot.Replace('\','/'))", '--console=plain', '--no-daemon')
if ($Abi -ne 'universal') { $gradleArguments += "-PlumaAbi=$Abi" }
if ($PlanOnly) {
    [pscustomobject]@{
        ProjectDirectory = $projectDirectory
        VersionName = $versionName
        ReleaseVersion = $releaseVersion
        Abi = $Abi
        ApkPath = $apkPath
        SourceDirectory = Join-Path $projectDirectory "dist\releases\$releaseVersion\source"
        ReportDirectory = $reportDirectory
        TestDirectory = $testDirectory
        RawReportDirectory = $rawReportDirectory
        BuildLog = $buildLog
        BuildRoot = $buildRoot
        GradleArguments = $gradleArguments
        InstallRequested = [bool]$Install
    }
    return
}
$sdkDirectory = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path -LiteralPath (Join-Path $sdkDirectory 'platforms\android-34\android.jar'))) {
    throw 'Instale Android SDK Platform 34 e Build Tools 34.0.0 e configure ANDROID_HOME.'
}
$localProperties = Join-Path $projectDirectory 'local.properties'
if (-not (Test-Path -LiteralPath $localProperties)) {
    Set-Content -LiteralPath $localProperties -Value ('sdk.dir=' + $sdkDirectory.Replace('\','/')) -Encoding utf8
}
New-Item -ItemType Directory -Path $reportDirectory, $testDirectory, $rawReportDirectory -Force | Out-Null
Push-Location $projectDirectory
try {
    & .\gradlew.bat @gradleArguments 2>&1 | Tee-Object -FilePath $buildLog
    if ($LASTEXITCODE -ne 0) { throw 'Compilação ou verificação falhou; confira a saída acima.' }
    New-Item -ItemType Directory -Path $apkDirectory -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $buildRoot 'app\outputs\apk\debug\app-debug.apk') -Destination $apkPath -Force
    $lintHtml = Join-Path $buildRoot 'app\reports\lint-results-debug.html'
    Copy-Item -LiteralPath $lintHtml -Destination $rawReportDirectory -Force
    & (Join-Path $PSScriptRoot '..\lib\ExportLint.ps1') -InputPath $lintHtml -OutputPath (Join-Path $reportDirectory 'lint-results-debug.html') -ProjectDirectory $projectDirectory -RawPath (Join-Path $rawReportDirectory 'lint-results-debug.html') | Out-Null
    $lintXml = Join-Path $buildRoot 'app\reports\lint-results-debug.xml'
    if (Test-Path -LiteralPath $lintXml) { Copy-Item -LiteralPath $lintXml -Destination $rawReportDirectory -Force }
    Get-ChildItem -LiteralPath (Join-Path $buildRoot 'app\test-results\testDebugUnitTest') -Filter 'TEST-*.xml' | Copy-Item -Destination $testDirectory -Force
    $apkHash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash
    $apkHash | Set-Content -LiteralPath (Join-Path $reportDirectory 'SHA256.txt')
    $apkHash | Set-Content -LiteralPath (Join-Path $apkDirectory "$apkName.sha256")
    if ($Install) {
        & (Join-Path $sdkDirectory 'platform-tools\adb.exe') install -r $apkPath
        if ($LASTEXITCODE -ne 0) { throw 'APK criado, mas instalação falhou. Conecte o celular com depuração USB autorizada.' }
    }
    Write-Output "Verificação concluída. APK: $apkPath"
    Write-Output "Relatórios: $reportDirectory"
} finally { Pop-Location }
