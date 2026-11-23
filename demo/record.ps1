# Records a demo video of GreeklishKeyboard.exe: types into demo.html in a temporary Edge window
# and records that part of the screen (including the suggestion bubble) with ffmpeg.
param([int]$DelayMs = 70)
Add-Type -AssemblyName System.Windows.Forms
Add-Type -ReferencedAssemblies System.Windows.Forms @"
using System; using System.Runtime.InteropServices; using System.Threading;
public static class KT {
  [StructLayout(LayoutKind.Sequential)] public struct KI { public ushort vk, scan; public uint flags, time; public IntPtr extra; }
  [StructLayout(LayoutKind.Sequential)] public struct MI { public int dx, dy; public uint data, flags, time; public IntPtr extra; }
  [StructLayout(LayoutKind.Explicit)] public struct U { [FieldOffset(0)] public KI ki; [FieldOffset(0)] public MI mi; }
  [StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public U u; }
  [DllImport("user32.dll")] public static extern uint SendInput(uint n, INPUT[] i, int size);
  [DllImport("user32.dll")] public static extern short VkKeyScan(char c);
  [DllImport("user32.dll")] public static extern uint MapVirtualKey(uint c, uint t);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  public struct PT { public int X, Y; }
  [DllImport("user32.dll")] public static extern bool GetCursorPos(out PT p);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindow(string cls, string title);
  public struct RC { public int L, T, R, B; }
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RC r);
  public static void F9() { K(0x78, false); K(0x78, true); }
  public static void Enter() { K(0x0D, false); K(0x0D, true); }
  static void K(ushort vk, bool up) {
    var i = new INPUT[1]; i[0].type = 1; i[0].u.ki.vk = vk; i[0].u.ki.scan = (ushort)MapVirtualKey(vk, 0);
    i[0].u.ki.flags = up ? 2u : 0u; SendInput(1, i, Marshal.SizeOf(typeof(INPUT)));
  }
  public static void Click() {
    var i = new INPUT[2]; i[0].type = 0; i[0].u.mi.flags = 2; i[1].type = 0; i[1].u.mi.flags = 4;
    SendInput(2, i, Marshal.SizeOf(typeof(INPUT)));
  }
  public static void Pump(int ms) {
    var end = DateTime.Now.AddMilliseconds(ms);
    while (DateTime.Now < end) { System.Windows.Forms.Application.DoEvents(); Thread.Sleep(5); }
  }
  public static void CtrlA() { K(0x11, false); K(0x41, false); K(0x41, true); K(0x11, true); }
  public static void Type(string s, int delay) {
    foreach (char c in s) {
      if (c == '\b') { K(0x08, false); K(0x08, true); Pump(delay); continue; }
      short r = VkKeyScan(c); ushort vk = (ushort)(r & 0xFF); bool shift = (r & 0x100) != 0;
      if (shift) K(0x10, false);
      K(vk, false); Pump(delay / 3); K(vk, true);
      if (shift) K(0x10, true);
      Pump(delay);
    }
  }
}
"@
[Console]::OutputEncoding = [Text.Encoding]::UTF8
# Records a demo of GreeklishKeyboard.exe: types into demo.html (temporary Edge window) and records that screen area.
[KT]::SetProcessDPIAware() | Out-Null
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$ffmpeg = & "$here\..\.venv\Scripts\python.exe" -c "import imageio_ffmpeg as f; print(f.get_ffmpeg_exe())"
$edge = "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
if (-not (Test-Path $edge)) { $edge = "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe" }
$profile = Join-Path $env:TEMP "gk-edge-demo"
$page = "file:///" + (Join-Path $here "demo.html").Replace([char]92, [char]47).Replace(" ", "%20")
$p = Start-Process $edge -ArgumentList "--user-data-dir=`"$profile`"", "--no-first-run", "--window-position=160,90", "--window-size=1000,720", "--app=$page" -PassThru
$h = [IntPtr]::Zero
for ($i = 0; $i -lt 30 -and $h -eq [IntPtr]::Zero; $i++) { [KT]::Pump(500); $h = [KT]::FindWindow([NullString]::Value, "Greeklish demo") }
[KT]::Pump(1500)
if ($h -eq [IntPtr]::Zero) { "demo window not found: stopping without typing anything"; Stop-Process -Id $p.Id -ErrorAction SilentlyContinue; exit 1 }
$r = New-Object KT+RC; [KT]::GetWindowRect($h, [ref]$r) | Out-Null
$x = $r.L + 8; $y = $r.T; $w = ($r.R - $r.L - 16); $hh = ($r.B - $r.T - 8)
$w -= $w % 2; $hh -= $hh % 2
"window at $x,$y size ${w}x$hh"
$old = New-Object KT+PT; [KT]::GetCursorPos([ref]$old) | Out-Null
# click into the message box (near the bottom of the window)
for ($i = 0; $i -lt 4 -and [KT]::GetForegroundWindow() -ne $h; $i++) {
  [KT]::SetCursorPos([int]($x + $w / 2), [int]($y + $hh - 140)) | Out-Null; [KT]::Pump(200); [KT]::Click(); [KT]::Pump(700)
}
if ([KT]::GetForegroundWindow() -ne $h) { "demo window is not in front: stopping without typing anything"; Stop-Process -Id $p.Id -ErrorAction SilentlyContinue; exit 1 }
# move the pointer out of the way (it is not recorded anyway)
[KT]::SetCursorPos([int]($x + $w - 30), [int]($y + $hh - 140)) | Out-Null
$out = Join-Path $here "raw.mp4"
Remove-Item $out -ErrorAction SilentlyContinue
$rec = Start-Process $ffmpeg -ArgumentList "-y -f gdigrab -framerate 30 -draw_mouse 0 -offset_x $x -offset_y $y -video_size ${w}x$hh -i desktop -t 48 -c:v libx264 -preset veryfast -crf 18 -pix_fmt yuv420p `"$out`"" -PassThru -WindowStyle Hidden
$t0 = Get-Date
[KT]::Pump(2500)

function Say([string]$t) {
  if ([KT]::GetForegroundWindow() -ne $h) { "demo window lost focus: stopping"; Stop-Process -Id $rec.Id -ErrorAction SilentlyContinue; Stop-Process -Id $p.Id -ErrorAction SilentlyContinue; exit 1 }
  [KT]::Type($t, 95); [KT]::Pump(700); [KT]::Enter(); [KT]::Pump(2600) }
Say "eimai sto gymastirio, 8a argisw ligo"
Say "pame gia kafe meta? kerasma egw"
Say "ok see you soon"
if ([KT]::GetForegroundWindow() -eq $h) { [KT]::F9() }
$cardAt = ((Get-Date) - $t0).TotalSeconds
[KT]::Pump(4000)

$rec.WaitForExit(60000) | Out-Null
Stop-Process -Id $p.Id -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='msedge.exe'" | Where-Object { $_.CommandLine -like "*gk-edge-demo*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -ErrorAction SilentlyContinue }
[KT]::SetCursorPos($old.X, $old.Y) | Out-Null
"recorded: $out"

# Final video: cut Edge's title bar off the top, start after the window settles,
# end 4 s after the closing card appears.
$final = Join-Path $here "GreeklishKeyboard-demo.mp4"
$end = [math]::Round($cardAt + 4, 1)
& $ffmpeg -hide_banner -loglevel error -y -ss 1.5 -to $end -i $out -vf "crop=iw:ih-34:0:34" -c:v libx264 -preset slow -crf 20 -pix_fmt yuv420p -movflags +faststart $final
"final: $final (ends at $end s)"
