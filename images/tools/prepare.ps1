# Prepares images\source\NN_*.jpg -> images\prepared\bg_NN.jpg (1280x720) + bg_NN_blur.jpg (640x360),
# then builds images\contact-sheet.jpg. Pure PowerShell 5.1 + System.Drawing (no Pillow on this machine).
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
public static class Fx {
    // 3 passes of a box blur ~= gaussian with sigma^2 = r*(r+1); also scales brightness by `gain`.
    public static void BlurDarken(Bitmap bmp, int r, double gain) {
        int w = bmp.Width, h = bmp.Height;
        var rect = new Rectangle(0, 0, w, h);
        var data = bmp.LockBits(rect, ImageLockMode.ReadWrite, PixelFormat.Format24bppRgb);
        int stride = data.Stride;
        byte[] px = new byte[stride * h];
        Marshal.Copy(data.Scan0, px, 0, px.Length);
        float[] buf = new float[w * h * 3];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) for (int c = 0; c < 3; c++)
            buf[(y * w + x) * 3 + c] = px[y * stride + x * 3 + c];
        for (int pass = 0; pass < 3; pass++) { Box(buf, w, h, r, true); Box(buf, w, h, r, false); }
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) for (int c = 0; c < 3; c++) {
            double v = buf[(y * w + x) * 3 + c] * gain;
            px[y * stride + x * 3 + c] = (byte)(v > 255 ? 255 : (v < 0 ? 0 : v + 0.5));
        }
        Marshal.Copy(px, 0, data.Scan0, px.Length);
        bmp.UnlockBits(data);
    }
    // Running-sum box blur along rows (horiz) or columns, clamped edges.
    static void Box(float[] b, int w, int h, int r, bool horiz) {
        int len = horiz ? w : h, lines = horiz ? h : w;
        float[] tmp = new float[len * 3];
        float norm = 1f / (2 * r + 1);
        for (int l = 0; l < lines; l++) {
            for (int c = 0; c < 3; c++) {
                float sum = 0;
                for (int i = -r; i <= r; i++) sum += b[Idx(Clamp(i, len), l, horiz, w) * 3 + c];
                for (int i = 0; i < len; i++) {
                    tmp[i * 3 + c] = sum * norm;
                    sum += b[Idx(Clamp(i + r + 1, len), l, horiz, w) * 3 + c] - b[Idx(Clamp(i - r, len), l, horiz, w) * 3 + c];
                }
            }
            for (int i = 0; i < len; i++) for (int c = 0; c < 3; c++) b[Idx(i, l, horiz, w) * 3 + c] = tmp[i * 3 + c];
        }
    }
    static int Clamp(int i, int len) { return i < 0 ? 0 : (i >= len ? len - 1 : i); }
    static int Idx(int i, int l, bool horiz, int w) { return horiz ? l * w + i : i * w + l; }
}
'@

$root = Split-Path $PSScriptRoot -Parent
$src = Join-Path $root 'source'
$out = Join-Path $root 'prepared'
$jpegCodec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }

function Save-Jpeg($bmp, $path, $quality, $maxBytes) {
    # Step quality down until the file fits the size budget (floor 60).
    for ($q = $quality; $q -ge 60; $q -= 5) {
        $p = New-Object System.Drawing.Imaging.EncoderParameters 1
        $p.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter ([System.Drawing.Imaging.Encoder]::Quality), ([long]$q)
        $bmp.Save($path, $jpegCodec, $p)
        if ((Get-Item $path).Length -le $maxBytes) { break }
    }
    return $q
}

function Resize-Crop($img, [System.Drawing.Rectangle]$srcRect, $w, $h) {
    $bmp = New-Object System.Drawing.Bitmap $w, $h, ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = 'HighQualityBicubic'; $g.PixelOffsetMode = 'HighQuality'; $g.CompositingQuality = 'HighQuality'; $g.SmoothingMode = 'HighQuality'
    $attr = New-Object System.Drawing.Imaging.ImageAttributes
    $attr.SetWrapMode([System.Drawing.Drawing2D.WrapMode]::TileFlipXY)  # avoids dark fringe at edges
    $g.DrawImage($img, (New-Object System.Drawing.Rectangle 0, 0, $w, $h), $srcRect.X, $srcRect.Y, $srcRect.Width, $srcRect.Height, [System.Drawing.GraphicsUnit]::Pixel, $attr)
    $g.Dispose(); $attr.Dispose()
    return $bmp
}

$report = @()
foreach ($f in Get-ChildItem (Join-Path $src '[0-9][0-9]_*.jpg') | Sort-Object Name) {
    $nn = $f.Name.Substring(0, 2)
    $img = [System.Drawing.Image]::FromFile($f.FullName)
    if ($nn -eq '00') {
        $rect = New-Object System.Drawing.Rectangle 0, 90, 1024, 576   # keeps ears (~y130) and mouth (~y560)
    } elseif ($img.Width / $img.Height -gt 16 / 9) {
        $cw = [int][math]::Round($img.Height * 16 / 9)
        $rect = New-Object System.Drawing.Rectangle ([int](($img.Width - $cw) / 2)), 0, $cw, $img.Height
    } else {
        $ch = [int][math]::Round($img.Width * 9 / 16)
        $rect = New-Object System.Drawing.Rectangle 0, ([int](($img.Height - $ch) / 2)), $img.Width, $ch
    }
    $bg = Resize-Crop $img $rect 1280 720
    $img.Dispose()
    $bgPath = Join-Path $out "bg_$nn.jpg"
    $q1 = Save-Jpeg $bg $bgPath 85 (250KB)

    $small = Resize-Crop $bg (New-Object System.Drawing.Rectangle 0, 0, 1280, 720) 640 360
    [Fx]::BlurDarken($small, 17, 0.75)   # sigma ~17.5 @640 = ~35 px @1280, -25% brightness
    $blurPath = Join-Path $out "bg_${nn}_blur.jpg"
    $q2 = Save-Jpeg $small $blurPath 80 (40KB)
    $bg.Dispose(); $small.Dispose()

    # self-check: exact output sizes and byte budgets
    foreach ($c in @(@($bgPath, 1280, 720, 250KB), @($blurPath, 640, 360, 40KB))) {
        $chk = [System.Drawing.Image]::FromFile($c[0]); $ok = ($chk.Width -eq $c[1] -and $chk.Height -eq $c[2]); $chk.Dispose()
        if (-not $ok) { throw "bad dimensions: $($c[0])" }
        if ((Get-Item $c[0]).Length -gt $c[3]) { Write-Warning "over size budget: $($c[0])" }
    }
    $report += [pscustomobject]@{ NN = $nn; bgKB = [math]::Round((Get-Item $bgPath).Length / 1KB); bgQ = $q1; blurKB = [math]::Round((Get-Item $blurPath).Length / 1KB); blurQ = $q2 }
}
$report | Format-Table -AutoSize

# Contact sheet: 4 columns of 320x180 thumbs, number in the top-left corner.
$bgs = Get-ChildItem (Join-Path $out 'bg_[0-9][0-9].jpg') | Sort-Object Name
$cols = 4; $tw = 320; $th = 180; $gap = 12
$rows = [math]::Ceiling($bgs.Count / $cols)
$sheet = New-Object System.Drawing.Bitmap ($cols * $tw + ($cols + 1) * $gap), ($rows * $th + ($rows + 1) * $gap), ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
$g = [System.Drawing.Graphics]::FromImage($sheet)
$g.Clear([System.Drawing.Color]::FromArgb(18, 18, 22))
$g.InterpolationMode = 'HighQualityBicubic'; $g.TextRenderingHint = 'AntiAliasGridFit'
$font = New-Object System.Drawing.Font 'Segoe UI', 18, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
$label = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(170, 0, 0, 0))
for ($i = 0; $i -lt $bgs.Count; $i++) {
    $x = $gap + ($i % $cols) * ($tw + $gap); $y = $gap + [math]::Floor($i / $cols) * ($th + $gap)
    $t = [System.Drawing.Image]::FromFile($bgs[$i].FullName)
    $g.DrawImage($t, $x, $y, $tw, $th); $t.Dispose()
    $g.FillRectangle($label, $x, $y, 40, 28)
    $g.DrawString($bgs[$i].BaseName.Substring(3, 2), $font, [System.Drawing.Brushes]::White, $x + 6, $y + 2)
}
$g.Dispose()
$q3 = Save-Jpeg $sheet (Join-Path $root 'contact-sheet.jpg') 85 (600KB)
$sheet.Dispose()
"contact-sheet.jpg written (q$q3)"
