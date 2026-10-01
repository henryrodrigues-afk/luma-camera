param(
    [Parameter(Mandatory = $true)][string]$InputPath,
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [Parameter(Mandatory = $true)][string]$ProjectDirectory,
    [string]$RawPath,
    [string]$OriginalDirectory
)
$ErrorActionPreference = 'Stop'
$inputFile = (Resolve-Path -LiteralPath $InputPath).Path
$projectRoot = (Resolve-Path -LiteralPath $ProjectDirectory).Path.TrimEnd('\', '/')
$projectBoundary = $projectRoot + [System.IO.Path]::DirectorySeparatorChar
$outputFile = [System.IO.Path]::GetFullPath($OutputPath)
if (-not $RawPath) { $RawPath = $inputFile }
$rawFile = (Resolve-Path -LiteralPath $RawPath).Path
if ($outputFile -eq $inputFile -or $outputFile -eq $rawFile) { throw 'The navigable copy must not overwrite the original or raw report.' }
$outputDirectory = Split-Path -Parent $outputFile
if (-not (Test-Path -LiteralPath $outputDirectory -PathType Container)) { throw 'Create the output report directory first.' }
$originDirectory = if ($OriginalDirectory) { [System.IO.Path]::GetFullPath($OriginalDirectory) } else { Split-Path -Parent $inputFile }
$rawHash = (Get-FileHash -LiteralPath $rawFile -Algorithm SHA256).Hash
if ((Get-FileHash -LiteralPath $inputFile -Algorithm SHA256).Hash -ne $rawHash) { throw 'The raw report must be a byte-identical copy of the input report.' }
$outputBaseUri = [uri]($outputDirectory.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar)
$projectName = [regex]::Escape((Split-Path -Leaf $projectRoot))
$projectSuffixPattern = '(?i)(?:^|/)(?:LumaCamera|' + $projectName + ')/(app/.*)$'
$links = [System.Collections.Generic.List[object]]::new()
$linkStats = [pscustomobject]@{ External = 0; Anchors = 0 }

function Get-RelativeLintUrl {
    param([string]$Target)
    $relative = $outputBaseUri.MakeRelativeUri([uri][System.IO.Path]::GetFullPath($Target))
    if ($relative.IsAbsoluteUri) { throw "Cannot make a relative link to another drive: $Target" }
    return $relative.OriginalString
}

function Resolve-LintLocalFile {
    param([string]$HrefPath)
    # Lint uses form-style '+' for spaces; try literal '+' first and retain %2B.
    if ($HrefPath -match '(?i)^file:') {
        # Parse before unescaping so encoded '#'/'?' remain filename characters.
        $variants = @(([uri]$HrefPath).LocalPath, ([uri]$HrefPath.Replace('+', ' ')).LocalPath) | Select-Object -Unique
    } else {
        $variants = @([uri]::UnescapeDataString($HrefPath), [uri]::UnescapeDataString($HrefPath.Replace('+', ' '))) | Select-Object -Unique
    }
    foreach ($variant in $variants) {
        if ([System.IO.Path]::IsPathRooted($variant)) {
            $candidate = $variant
        } else {
            $candidate = [System.IO.Path]::GetFullPath((Join-Path $originDirectory $variant))
        }
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    # Archived reports may have lost their original build directory.
    foreach ($variant in $variants) {
        $suffix = [regex]::Match($variant.Replace('\', '/'), $projectSuffixPattern)
        if (-not $suffix.Success) { continue }
        $candidate = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $suffix.Groups[1].Value))
        if ($candidate.StartsWith($projectBoundary, [System.StringComparison]::OrdinalIgnoreCase) -and
            (Test-Path -LiteralPath $candidate -PathType Leaf)) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    throw "Cannot resolve a local lint link: $HrefPath"
}

$html = [System.IO.File]::ReadAllText($inputFile, [System.Text.Encoding]::UTF8)
$html = [regex]::Replace($html, '(?is)(\bhref\s*=\s*)(["''])(.*?)\2', [System.Text.RegularExpressions.MatchEvaluator]{
    param($linkMatch)
    $href = [System.Net.WebUtility]::HtmlDecode($linkMatch.Groups[3].Value).Trim()
    if (-not $href -or $href.StartsWith('#') -or $href.StartsWith('?')) {
        $linkStats.Anchors++
        return $linkMatch.Value
    }
    $isDrivePath = $href -match '^[A-Za-z]:[/\\]'
    if ($href.StartsWith('//') -or (($href -match '^[A-Za-z][A-Za-z0-9+.-]*:') -and
        $href -notmatch '(?i)^file:' -and -not $isDrivePath)) {
        $linkStats.External++
        return $linkMatch.Value
    }
    $parts = [regex]::Match($href, '^([^?#]*)([?#].*)?$')
    $target = Resolve-LintLocalFile $parts.Groups[1].Value
    $relativeUrl = (Get-RelativeLintUrl $target) + $parts.Groups[2].Value
    $links.Add([pscustomobject]@{ Original = $linkMatch.Groups[3].Value; Target = $target; Href = $relativeUrl })
    $encodedUrl = [System.Net.WebUtility]::HtmlEncode($relativeUrl)
    return $linkMatch.Groups[1].Value + $linkMatch.Groups[2].Value + $encodedUrl + $linkMatch.Groups[2].Value
})
$rawHref = [System.Net.WebUtility]::HtmlEncode((Get-RelativeLintUrl $rawFile))
$notice = '<aside role="note" data-luma-lint-navigation="true" style="max-width:860px;margin:16px auto;padding:12px;background:#fff3cd;border:1px solid #e0cb85;">Cópia navegável: links locais ajustados para esta pasta. <a href="' + $rawHref + '">Relatório bruto preservado</a>.</aside>'
$container = [regex]::Match($html, '(?i)<main\b[^>]*>')
if (-not $container.Success) { $container = [regex]::Match($html, '(?i)<body\b[^>]*>') }
if (-not $container.Success) { throw 'Lint report has no main or body element for the navigation notice.' }
$html = $html.Insert($container.Index + $container.Length, [Environment]::NewLine + $notice + [Environment]::NewLine)
[System.IO.File]::WriteAllText($outputFile, $html, [System.Text.UTF8Encoding]::new($false))
if ((Get-FileHash -LiteralPath $rawFile -Algorithm SHA256).Hash -ne $rawHash) { throw 'The raw report changed during export.' }
[pscustomobject]@{
    OutputPath = $outputFile
    RawPath = $rawFile
    RawSHA256 = $rawHash
    LocalLinksAdjusted = $links.Count
    ExternalLinksPreserved = $linkStats.External
    AnchorsPreserved = $linkStats.Anchors
    Links = $links.ToArray()
}
