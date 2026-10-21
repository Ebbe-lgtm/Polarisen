# Pre-bakes the Liquid Glass look for the launcher (Android 9 has no realtime blur):
#   dock_NN.jpg  1201x125  = dock region of bg_NN (x 40..1241, y 496..621): blur ~9px, lens bent inward at the edges,
#                            saturation x1.7, brightness x1.06  (same as the mockup's SVG lens filter)
#   grid_NN.jpg   640x360  = whole bg_NN: blur ~42px@1280, saturation x1.5, brightness x0.6, + rgba(8,12,16,.28)
# and copies bg/dock/grid into launcher\app\src\main\res\drawable-nodpi. PowerShell 5.1 + System.Drawing.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
public static class Glass {
    public static float[] Read(Bitmap bmp) {
        int w = bmp.Width, h = bmp.Height;
        var data = bmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.ReadOnly, PixelFormat.Format24bppRgb);
        byte[] px = new byte[data.Stride * h]; Marshal.Copy(data.Scan0, px, 0, px.Length);
        float[] f = new float[w * h * 3];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) for (int c = 0; c < 3; c++) f[(y * w + x) * 3 + c] = px[y * data.Stride + x * 3 + c];
        bmp.UnlockBits(data); return f;
    }
    public static Bitmap Write(float[] f, int w, int h) {
        var bmp = new Bitmap(w, h, PixelFormat.Format24bppRgb);
        var data = bmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.WriteOnly, PixelFormat.Format24bppRgb);
        byte[] px = new byte[data.Stride * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) for (int c = 0; c < 3; c++) {
            float v = f[(y * w + x) * 3 + c]; px[y * data.Stride + x * 3 + c] = (byte)(v > 255 ? 255 : (v < 0 ? 0 : v + 0.5f)); }
        Marshal.Copy(px, 0, data.Scan0, px.Length); bmp.UnlockBits(data); return bmp;
    }
    public static float[] Crop(float[] f, int w, int x0, int y0, int cw, int ch) {
        float[] o = new float[cw * ch * 3];
        for (int y = 0; y < ch; y++) Array.Copy(f, ((y0 + y) * w + x0) * 3, o, y * cw * 3, cw * 3);
        return o;
    }
    // 3 passes of a box blur ~= gaussian with sigma^2 = r*(r+1)
    public static void Blur(float[] b, int w, int h, int r) { for (int p = 0; p < 3; p++) { Box(b, w, h, r, true); Box(b, w, h, r, false); } }
    static void Box(float[] b, int w, int h, int r, bool horiz) {
        int len = horiz ? w : h, lines = horiz ? h : w; float[] tmp = new float[len * 3]; float norm = 1f / (2 * r + 1);
        for (int l = 0; l < lines; l++) {
            for (int c = 0; c < 3; c++) {
                float sum = 0;
                for (int i = -r; i <= r; i++) sum += b[Idx(Cl(i, len), l, horiz, w) * 3 + c];
                for (int i = 0; i < len; i++) { tmp[i * 3 + c] = sum * norm; sum += b[Idx(Cl(i + r + 1, len), l, horiz, w) * 3 + c] - b[Idx(Cl(i - r, len), l, horiz, w) * 3 + c]; }
            }
            for (int i = 0; i < len; i++) for (int c = 0; c < 3; c++) b[Idx(i, l, horiz, w) * 3 + c] = tmp[i * 3 + c];
        }
    }
    static int Cl(int i, int len) { return i < 0 ? 0 : (i >= len ? len - 1 : i); }
    static int Idx(int i, int l, bool horiz, int w) { return horiz ? l * w + i : i * w + l; }
    // Lens profile: +1 inside the outer pad, ramps to 0 over `band`, flat middle, ramps to -1 on the far side (= SVG map R/G 255..128..0)
    static float Ramp(int i, int n, int pad, int band) {
        if (i <= pad) return 1f; if (i < pad + band) return 1f - (i - pad) / (float)band;
        if (i <= n - pad - band) return 0f; if (i < n - pad) return -(i - (n - pad - band)) / (float)band; return -1f;
    }
    public static float[] Lens(float[] s, int w, int h, int pad, int band, float maxShift) {
        float[] o = new float[s.Length];
        for (int y = 0; y < h; y++) { float dy = maxShift * Ramp(y, h, pad, band);
            for (int x = 0; x < w; x++) { float dx = maxShift * Ramp(x, w, pad, band);
                float sx = Math.Max(0, Math.Min(w - 1.001f, x + dx)), sy = Math.Max(0, Math.Min(h - 1.001f, y + dy));
                int x0 = (int)sx, y0 = (int)sy; float fx = sx - x0, fy = sy - y0;
                for (int c = 0; c < 3; c++) {
                    float a = s[(y0 * w + x0) * 3 + c], b = s[(y0 * w + x0 + 1) * 3 + c], d = s[((y0 + 1) * w + x0) * 3 + c], e = s[((y0 + 1) * w + x0 + 1) * 3 + c];
                    o[(y * w + x) * 3 + c] = (a * (1 - fx) + b * fx) * (1 - fy) + (d * (1 - fx) + e * fx) * fy;
                } } }
        return o;
    }
    // Legibility scrim baked into the picture (was drawn live as 3 full-screen gradients, too slow on the TV GPU):
    // dark ellipse top-centre (clock), ellipse left of the dock (caption), and a floor gradient.
    static float Ell(float x, float y, float cx, float cy, float rx, float ry, float a, float end) {
        float d = (float)Math.Sqrt((x - cx) * (x - cx) / (rx * rx) + (y - cy) * (y - cy) / (ry * ry));
        return d >= end ? 0 : a * (1 - d / end);
    }
    public static void Scrim(float[] f, int w, int h) {
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float k = (1 - Ell(x, y, w / 2f, 0, .36f * w, .34f * h, 158 / 255f, .8f))
                    * (1 - Ell(x, y, 0, .62f * h, .4f * w, .3f * h, 102 / 255f, .75f))
                    * (1 - 115 / 255f * Math.Max(0, Math.Min(1, (y - .65f * h) / (.35f * h))));
            int i = (y * w + x) * 3; f[i] *= k; f[i + 1] *= k; f[i + 2] *= k;
        }
    }
    // CSS saturate(s) then brightness(k), then optional flat overlay colour (BGR order in buffer) with alpha
    public static void Color(float[] f, float sat, float k, float ob, float og, float or, float oa) {
        for (int i = 0; i < f.Length; i += 3) {
            float b = f[i], g = f[i + 1], r = f[i + 2];
            float r2 = (0.213f + 0.787f * sat) * r + (0.715f - 0.715f * sat) * g + (0.072f - 0.072f * sat) * b;
            float g2 = (0.213f - 0.213f * sat) * r + (0.715f + 0.285f * sat) * g + (0.072f - 0.072f * sat) * b;
            float b2 = (0.213f - 0.213f * sat) * r + (0.715f - 0.715f * sat) * g + (0.072f + 0.928f * sat) * b;
            f[i] = Math.Max(0, Math.Min(255, b2 * k)) * (1 - oa) + ob * oa;
            f[i + 1] = Math.Max(0, Math.Min(255, g2 * k)) * (1 - oa) + og * oa;
            f[i + 2] = Math.Max(0, Math.Min(255, r2 * k)) * (1 - oa) + or * oa;
        }
    }
}
'@

$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$prep = Join-Path $root 'images\prepared'
$res = Join-Path $root 'launcher\app\src\main\res\drawable-nodpi'
New-Item -ItemType Directory -Force $res | Out-Null
$jpeg = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
function Save-Jpeg($bmp, $path, $q) {
    $p = New-Object System.Drawing.Imaging.EncoderParameters 1
    $p.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter ([System.Drawing.Imaging.Encoder]::Quality), ([long]$q)
    $bmp.Save($path, $jpeg, $p)
}

foreach ($f in Get-ChildItem (Join-Path $prep 'bg_[0-9][0-9].jpg') | Sort-Object Name) {
    $nn = $f.BaseName.Substring(3, 2)
    $src = New-Object System.Drawing.Bitmap $f.FullName
    $px = [Glass]::Read($src); $src.Dispose()
    [Glass]::Scrim($px, 1280, 720)                          # everything below (bg, dock glass, grid) sees the scrimmed picture
    $bg = [Glass]::Write($px, 1280, 720)
    Save-Jpeg $bg (Join-Path $res "bg_$nn.jpg") 88

    # dock: lens box is 24 px larger than the dock on every side so the bent edge samples real pixels
    $box = [Glass]::Crop($px, 1280, 16, 472, 1249, 173)
    [Glass]::Blur($box, 1249, 173, 9)                       # sigma ~9.5
    $box = [Glass]::Lens($box, 1249, 173, 24, 26, 23)
    $dock = [Glass]::Crop($box, 1249, 24, 24, 1201, 125)
    [Glass]::Color($dock, 1.7, 1.06, 0, 0, 0, 0)
    $out = [Glass]::Write($dock, 1201, 125); Save-Jpeg $out (Join-Path $res "dock_$nn.jpg") 85; $out.Dispose()

    # grid: downscale, heavy blur, darker
    $small = New-Object System.Drawing.Bitmap 640, 360, ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g = [System.Drawing.Graphics]::FromImage($small); $g.InterpolationMode = 'HighQualityBicubic'; $g.DrawImage($bg, 0, 0, 640, 360); $g.Dispose()
    $sp = [Glass]::Read($small); $small.Dispose()
    [Glass]::Blur($sp, 640, 360, 20)                        # sigma ~20.5 @640 = ~42 @1280
    [Glass]::Color($sp, 1.5, 0.6, 16, 12, 8, 0.28)          # overlay rgb(8,12,16) stored as BGR
    $out = [Glass]::Write($sp, 640, 360); Save-Jpeg $out (Join-Path $res "grid_$nn.jpg") 80; $out.Dispose()

    $bg.Dispose()
    # self-check sizes
    foreach ($c in @(@("dock_$nn.jpg", 1201, 125), @("grid_$nn.jpg", 640, 360), @("bg_$nn.jpg", 1280, 720))) {
        $i = [System.Drawing.Image]::FromFile((Join-Path $res $c[0])); $ok = $i.Width -eq $c[1] -and $i.Height -eq $c[2]; $i.Dispose()
        if (-not $ok) { throw "bad size $($c[0])" }
    }
    "$nn ok"
}
