param([string]$Version)
$ErrorActionPreference = 'Stop'
$tok = $env:MODRINTH_TOKEN
if (-not $tok) { throw "缺少环境变量 MODRINTH_TOKEN（勿把密钥写进仓库）" }
$projId = 'SvKPzm09'
# 中文路径在子进程编码链路会坏——先 cd 到工程目录再用相对路径
Set-Location 'F:\vibe coding工程\sfx\worldreplay\nf-1.21.1'
$jar = (Resolve-Path "build\libs\worldreplay-$Version-1.21.1-neoforge.jar").Path
$jarName = "worldreplay-$Version-1.21.1-neoforge.jar"

$changelog = if ($Version -eq '0.1.1') {
    'Bug fix release (16 fixes). HIGH: recordings now visible on remote clients via S2C list + chunked file streaming; slow-motion 0.25x/0.5x actually worked (was 1x due to rounding); GUI buttons no longer negative-width on small panels. MED: replay starts at first event (no more hours of empty air); render no longer fires 5-8x per frame; fade direction fixed (current action now brightest); stop button works reliably; layout overlap fixed. LOW: /worldreplay command naming, corrupt .wrp handling, ordered timeline, cleanup after playback.'
} else { "WorldReplay $Version" }

$data = [ordered]@{
    project_id     = $projId
    version_number = $Version
    version_title  = "WorldReplay $Version"
    changelog      = $changelog
    game_versions  = @('1.21.1')
    loaders        = @('neoforge')
    version_type   = 'release'
    featured       = $true
    file_parts     = @($jarName)
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
$tmp = Join-Path $env:TEMP "wr_upload_$Version.bin"
[System.IO.File]::WriteAllBytes($tmp, $ms.ToArray())

$r = curl.exe -s --noproxy '*' -X POST -H "Authorization: $tok" -H "User-Agent: worldreplay-setup/0.1" -H "Content-Type: multipart/form-data; boundary=$boundary" --data-binary "@$tmp" 'https://api.modrinth.com/v2/version' -w "`nHTTP:%{http_code}"
Remove-Item $tmp -Force
$out = $r -join "`n"
if ($out -match 'HTTP:200') { Write-Host "OK: $Version uploaded" }
else { Write-Host "FAILED: $($out.Substring(0, [Math]::Min(400, $out.Length)))" }
