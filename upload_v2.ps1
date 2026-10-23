param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$Changelog,
    [string]$JarPath,
    [string[]]$GameVersions = @('1.21.1'),
    [string[]]$Loaders = @('neoforge')
)
$ErrorActionPreference = 'Stop'
$tok = $env:MODRINTH_TOKEN
if (-not $tok) { throw "缺少环境变量 MODRINTH_TOKEN（勿把密钥写进仓库）" }

# Infer JarPath when not given (keeps old call style working).
if (-not $JarPath) {
    $root = 'F:\vibe coding工程\sfx\worldreplay'
    if ($Loaders[0] -eq 'neoforge') {
        $JarPath = Join-Path $root "nf-1.21.1\build\libs\worldreplay-$Version-1.21.1-neoforge.jar"
    } elseif ($Loaders[0] -eq 'fabric') {
        $JarPath = Join-Path $root "fabric-$($GameVersions[0])\build\libs\worldreplay-$Version-$($GameVersions[0])-fabric.jar"
    } else {
        throw "unknown loader: $($Loaders[0])"
    }
}
$jar = (Resolve-Path -LiteralPath $JarPath).Path
$jarName = Split-Path -Leaf $jar

$data = [ordered]@{
    project_id     = 'SvKPzm09'
    version_number = $Version
    version_title  = "WorldReplay $Version"
    changelog      = $Changelog
    game_versions  = @($GameVersions)
    loaders        = @($Loaders)
    version_type   = 'release'
    featured       = $false
    file_parts     = @($jarName)
    dependencies   = @()
}
$dataJson = $data | ConvertTo-Json -Depth 6 -Compress

$boundary = '----wr' + [guid]::NewGuid().ToString('N')
$CRLF = "`r`n"
$part1 = "--$boundary$CRLF"
$part1 += "Content-Disposition: form-data; name=`"data`"$CRLF"
$part1 += "Content-Type: application/json$CRLF$CRLF"
$part1 += $dataJson + $CRLF
$part2 = "--$boundary$CRLF"
$part2 += "Content-Disposition: form-data; name=`"$jarName`"; filename=`"$jarName`"$CRLF"
$part2 += "Content-Type: application/java-archive$CRLF$CRLF"
$partEnd = "$CRLF--$boundary--$CRLF"

$ms = New-Object System.IO.MemoryStream
$w = New-Object System.IO.BinaryWriter($ms)
$w.Write([System.Text.Encoding]::UTF8.GetBytes($part1))
$w.Write([System.Text.Encoding]::UTF8.GetBytes($part2))
$w.Write([System.IO.File]::ReadAllBytes($jar))
$w.Write([System.Text.Encoding]::UTF8.GetBytes($partEnd))
$w.Flush()
$tmp = Join-Path $env:TEMP "wr_upload_$($Loaders[0]).bin"
[System.IO.File]::WriteAllBytes($tmp, $ms.ToArray())
$w.Dispose(); $ms.Dispose()

$r = curl.exe -s --noproxy '*' --ssl-no-revoke -X POST -H "Authorization: $tok" -H "User-Agent: worldreplay-setup/0.1" -H "Content-Type: multipart/form-data; boundary=$boundary" --data-binary "@$tmp" 'https://api.modrinth.com/v2/version' -w "`nHTTP:%{http_code}"
Remove-Item $tmp -Force
$out = $r -join "`n"
if ($out -match 'HTTP:200' -or $out -match 'HTTP:201') { Write-Host "OK: $Version ($($Loaders[0])) uploaded" }
else { Write-Host "FAILED: $($out.Substring(0, [Math]::Min(500, $out.Length)))" }
