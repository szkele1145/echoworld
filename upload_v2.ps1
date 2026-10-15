param([string]$Version, [string]$Changelog)
$ErrorActionPreference = 'Stop'
Set-Location 'F:\vibe coding工程\sfx\worldreplay\nf-1.21.1'
$tok = 'mrp_HhdbSQhqUdMNWM4Gl6nEpt6Oz2bIVVvXdQMnqgoayoVHdNGUaB1kbUv3Cn47'
$jarName = "worldreplay-$Version-1.21.1-neoforge.jar"
$jar = (Resolve-Path "build\libs\$jarName").Path

$data = [ordered]@{
    project_id     = 'SvKPzm09'
    version_number = $Version
    version_title  = "WorldReplay $Version"
    changelog      = $Changelog
    game_versions  = @('1.21.1')
    loaders        = @('neoforge')
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
$tmp = Join-Path $env:TEMP "wr_upload_$Version.bin"
[System.IO.File]::WriteAllBytes($tmp, $ms.ToArray())

$r = curl.exe -s --noproxy '*' -X POST -H "Authorization: $tok" -H "User-Agent: worldreplay-setup/0.1" -H "Content-Type: multipart/form-data; boundary=$boundary" --data-binary "@$tmp" 'https://api.modrinth.com/v2/version' -w "`nHTTP:%{http_code}"
Remove-Item $tmp -Force
$out = $r -join "`n"
if ($out -match 'HTTP:200') { Write-Host "OK: $Version uploaded" }
else { Write-Host "FAILED: $($out.Substring(0, [Math]::Min(400, $out.Length)))" }
