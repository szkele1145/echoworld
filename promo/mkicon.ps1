Add-Type -AssemblyName System.Drawing

# WorldReplay 图标：深色底 + 幽灵方块线框（青/红/金三色）+ 回放弧
$size = 512
$bmp = New-Object System.Drawing.Bitmap($size, $size)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.Clear([System.Drawing.Color]::FromArgb(255, 22, 24, 29))   # #16181D

# 背景渐变网格（游戏感）
$gridPen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(40, 255, 255, 255), 1)
for($i = 0; $i -lt $size; $i += 32){
    $g.DrawLine($gridPen, $i, 0, $i, $size)
    $g.DrawLine($gridPen, 0, $i, $size, $i)
}

# 中央幽灵立方体（等距投影）：三面
$cx = 256; $cy = 250
$w = 150; $hh = 75; $vh = 110
# 顶面（青色 50%）
$topPts = @(
    (New-Object System.Drawing.Point($cx, $cy - $hh)),
    (New-Object System.Drawing.Point($cx + $w, $cy)),
    (New-Object System.Drawing.Point($cx, $cy + $hh)),
    (New-Object System.Drawing.Point($cx - $w, $cy))
)
$g.FillPolygon((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(110, 0, 200, 255))), $topPts)
# 左面（深青）
$leftPts = @(
    (New-Object System.Drawing.Point($cx - $w, $cy)),
    (New-Object System.Drawing.Point($cx, $cy + $hh)),
    (New-Object System.Drawing.Point($cx, $cy + $hh + $vh)),
    (New-Object System.Drawing.Point($cx - $w, $cy + $vh))
)
$g.FillPolygon((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(80, 0, 120, 180))), $leftPts)
# 右面（蓝）
$rightPts = @(
    (New-Object System.Drawing.Point($cx + $w, $cy)),
    (New-Object System.Drawing.Point($cx, $cy + $hh)),
    (New-Object System.Drawing.Point($cx, $cy + $hh + $vh)),
    (New-Object System.Drawing.Point($cx + $w, $cy + $vh))
)
$g.FillPolygon((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(80, 30, 80, 200))), $rightPts)

# 立方体边线（白 60%）
$edgePen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(160, 255, 255, 255), 4)
$allEdges = @(
    @($topPts[0], $topPts[1]), @($topPts[1], $topPts[2]), @($topPts[2], $topPts[3]), @($topPts[3], $topPts[0]),
    @((New-Object System.Drawing.Point($cx - $w, $cy)), (New-Object System.Drawing.Point($cx - $w, $cy + $vh))),
    @((New-Object System.Drawing.Point($cx + $w, $cy)), (New-Object System.Drawing.Point($cx + $w, $cy + $vh))),
    @((New-Object System.Drawing.Point($cx, $cy + $hh)), (New-Object System.Drawing.Point($cx, $cy + $hh + $vh))),
    @((New-Object System.Drawing.Point($cx - $w, $cy + $vh)), (New-Object System.Drawing.Point($cx, $cy + $hh + $vh))),
    @((New-Object System.Drawing.Point($cx, $cy + $hh + $vh)), (New-Object System.Drawing.Point($cx + $w, $cy + $vh))),
    @((New-Object System.Drawing.Point($cx - $w, $cy + $vh)), (New-Object System.Drawing.Point($cx - $w, $cy))),
    @((New-Object System.Drawing.Point($cx + $w, $cy + $vh)), (New-Object System.Drawing.Point($cx + $w, $cy)))
)
foreach($e in $allEdges){ $g.DrawLine($edgePen, $e[0], $e[1]) }

# 回放弧（右上角，青→金渐变弧+播放三角）
$arcPen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(220, 255, 215, 0), 14)
$g.DrawArc($arcPen, 330, 40, 130, 130, 120, 240)
# 播放三角
$tri = @(
    (New-Object System.Drawing.Point(390, 75)),
    (New-Object System.Drawing.Point(390, 135)),
    (New-Object System.Drawing.Point(445, 105))
)
$g.FillPolygon((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(230, 255, 215, 0))), $tri)

# 底部标签 W
$font = New-Object System.Drawing.Font('Consolas', 56, [System.Drawing.FontStyle]::Bold)
$g.DrawString('WR', $font, (New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(230, 230, 237, 243))), 185, 395)

$g.Dispose()

# 输出多种尺寸
$bmp.Save("$PSScriptRoot\icon_512.png", [System.Drawing.Imaging.ImageFormat]::Png)
$icon256 = New-Object System.Drawing.Bitmap($bmp, 256, 256)
$icon256.Save("$PSScriptRoot\icon_256.png", [System.Drawing.Imaging.ImageFormat]::Png)
$icon64 = New-Object System.Drawing.Bitmap($bmp, 64, 64)
$icon64.Save("$PSScriptRoot\icon_64.png", [System.Drawing.Imaging.ImageFormat]::Png)
Write-Host icons-generated
