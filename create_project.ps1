param()
$mr = $env:MODRINTH_TOKEN
if (-not $mr) { throw "缺少环境变量 MODRINTH_TOKEN（勿把密钥写进仓库）" }
$proj = [ordered]@{
    slug = 'echoworld'
    title = 'EchoWorld'
    description = 'Full action recording and replay for Minecraft - see what players DID, not just where they went. Records block place/break/use and container opens per-player, replays them in-world as ghost wireframes with floating labels at 50% alpha so replays are instantly recognizable.'
    body = @'
## EchoWorld

**Record what players did. Replay it in the world.**

While GlowTrack answers *where players went*, EchoWorld answers *what they did*.

### Features

- **Automatic recording** - every joining player gets a recording session; block place/break/use and container opens are captured with tick precision
- **Manual control** - OP GUI (O key or `/echoreplay gui`) to pick recording targets (multi-select or all players), start/stop on demand
- **Persistent recordings** - sessions export to versioned binary `.ecw` files in `echoworld/recordings/`
- **Ghost replay** - open a recording from the GUI and watch events replay in-world: colored wireframe boxes (place=cyan, break=red, use=gold) with floating labels, all at 50% opacity so replays are instantly recognizable
- **Timeline engine** - seek anywhere, speed 0.25x-64x

### Commands

- `/echoreplay gui` - opens the recorder management screen
- `/echoreplay <file>` - validate a recording server-side

### Compatibility

Built on vanilla block/entity events - modded blocks (MTR, Create, etc.) are captured automatically without special integration.

### Planned

- Fabric builds for 1.20.1 / 1.19.4 / 1.19.2
- Entity movement ghost players
- Timeline markers in world

---

License: All Rights Reserved. Author: GlowTrack team.
'@
    categories = @('utility', 'management')
    client_side = 'optional'
    server_side = 'required'
    license = @{ id = 'all-rights-reserved' }
    status = 'draft'
}
$json = $proj | ConvertTo-Json -Depth 6
$tmp = Join-Path $env:TEMP 'echo_proj.json'
[System.IO.File]::WriteAllText($tmp, $json, [System.Text.UTF8Encoding]::new($false))
$r = curl.exe -s --noproxy '*' -X POST -H "Authorization: $mr" -H "Content-Type: application/json" --data "@$tmp" 'https://api.modrinth.com/v2/project' -w "`nHTTP:%{http_code}"
Remove-Item $tmp -Force
$out = $r -join "`n"
if($out -match 'HTTP:200'){ Write-Host 'project-created' } else { Write-Host $out.Substring(0,[Math]::Min(400,$out.Length)) }
