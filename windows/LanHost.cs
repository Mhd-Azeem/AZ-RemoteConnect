using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Collections.Concurrent;
using System.Diagnostics;
using NAudio.Wave;

namespace AZRemoteConnect;

internal sealed class LanHost : IAsyncDisposable
{
    private readonly TcpListener listener = new(IPAddress.Any, 48765);
    private readonly X509Certificate2 certificate = RemoteTransport.MakeCertificate();
    private readonly CancellationTokenSource stop = new();
    private readonly Func<Task<bool>> approve;
    private int attempts;
    public string PairCode { get; } = RandomNumberGenerator.GetInt32(100000, 1000000).ToString();
    public string Fingerprint => RemoteTransport.Fingerprint(certificate);
    public string VerificationCode => Fingerprint[..6];
    public LanHost(Func<Task<bool>> approve) => this.approve = approve;
    public void Start() { listener.Start(); _ = AcceptLoop(); }
    private async Task AcceptLoop()
    {
        while (!stop.IsCancellationRequested)
        {
            TcpClient client;
            try { client = await listener.AcceptTcpClientAsync(stop.Token); }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            _ = HandleClient(client);
        }
    }
    private async Task HandleClient(TcpClient client)
    {
        using (client)
        {
            try
            {
                if (Interlocked.Increment(ref attempts) > 30) return;
                using var tls = new SslStream(client.GetStream(), false);
                await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions { ServerCertificate = certificate, EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13 });
                using var hello = await RemoteTransport.ReadAsync(tls, stop.Token);
                if (RemoteTransport.Field(hello, "type") != "pair" || !CryptographicOperations.FixedTimeEquals(System.Text.Encoding.UTF8.GetBytes(RemoteTransport.Field(hello,"code").PadRight(6).Substring(0,6)), System.Text.Encoding.UTF8.GetBytes(PairCode))) return;
                if (!await approve()) return;
                await RemoteTransport.SendAsync(tls, new { type = "approved" }, stop.Token);
                using var sessionStop = CancellationTokenSource.CreateLinkedTokenSource(stop.Token);
                var settings = new StreamSettings();
                var frames = SendFramesAndAudio(tls, sessionStop.Token, settings);
                try
                {
                    while (!sessionStop.IsCancellationRequested)
                    {
                        using var command = await RemoteTransport.ReadAsync(tls, sessionStop.Token);
                        var kind = RemoteTransport.Field(command, "type");
                        if (kind == "text")
                        {
                            var value = RemoteTransport.Field(command, "value");
                            if (value.Length <= 200) foreach (var unit in value) SendUnicode(unit);
                        }
                        else if (kind == "key")
                        {
                            var key = RemoteTransport.Field(command, "key");
                            ushort vk = key switch {
                                "BACKSPACE" => 0x08, "ENTER" => 0x0D, "TAB" => 0x09,
                                "ESC" => 0x1B, "DELETE" => 0x2E, "LEFT" => 0x25,
                                "UP" => 0x26, "RIGHT" => 0x27, "DOWN" => 0x28,
                                "HOME" => 0x24, "END" => 0x23, "PAGEUP" => 0x21,
                                "PAGEDOWN" => 0x22, "SPACE" => 0x20, _ => (ushort)0
                            };
                            if (vk == 0 && key.Length == 1 && ((key[0] >= 'A' && key[0] <= 'Z') || (key[0] >= '0' && key[0] <= '9'))) vk = key[0];
                            if (vk != 0) SendShortcut(vk, RemoteTransport.Field(command, "modifiers"));
                        }
                        else if (kind == "quality")
                        {
                            var profile = RemoteTransport.Field(command, "profile");
                            if (profile is "smooth" or "balanced" or "data") settings.Profile = profile;
                        }
                        else if (kind == "button")
                        {
                            var action = RemoteTransport.Field(command, "action");
                            if (RemoteTransport.Field(command, "button") == "left")
                            {
                                if (action == "down") mouse_event(0x0002,0,0,0,UIntPtr.Zero);
                                if (action == "up") mouse_event(0x0004,0,0,0,UIntPtr.Zero);
                            }
                        }
                        else if (kind == "scroll")
                        {
                            if (int.TryParse(RemoteTransport.Field(command, "delta"), out var delta))
                                mouse_event(0x0800, 0, 0, unchecked((uint)Math.Clamp(delta, -480, 480)), UIntPtr.Zero);
                        }
                        else if (kind == "relative")
                        {
                            if (double.TryParse(RemoteTransport.Field(command, "dx"), out var dx) &&
                                double.TryParse(RemoteTransport.Field(command, "dy"), out var dy) &&
                                double.IsFinite(dx) && double.IsFinite(dy) &&
                                Math.Abs(dx) <= 2000 && Math.Abs(dy) <= 2000)
                            {
                                var pos = Cursor.Position;
                                Cursor.Position = new Point(pos.X + (int)dx, pos.Y + (int)dy);
                            }
                        }
                        else if (kind == "click")
                        {
                            var button = RemoteTransport.Field(command, "button");
                            if (button == "left")
                            {
                                int times = RemoteTransport.Field(command, "count") == "2" ? 2 : 1;
                                for (int i = 0; i < times; i++) { mouse_event(0x0002,0,0,0,UIntPtr.Zero); mouse_event(0x0004,0,0,0,UIntPtr.Zero); }
                            }
                        }
                        else if (kind == "mouse")
                        {
                            if (!double.TryParse(RemoteTransport.Field(command, "x"), out var x) || !double.TryParse(RemoteTransport.Field(command, "y"), out var y)) continue;
                            if (x < 0 || x > 1 || y < 0 || y > 1) continue;
                            var bounds = SystemInformation.VirtualScreen;
                            Cursor.Position = new Point(bounds.Left + (int)(x * (bounds.Width-1)), bounds.Top + (int)(y * (bounds.Height-1)));
                            var action = RemoteTransport.Field(command, "action");
                            if (action == "down") mouse_event(0x0002, 0, 0, 0, UIntPtr.Zero);
                            if (action == "up") mouse_event(0x0004, 0, 0, 0, UIntPtr.Zero);
                            if (action == "right") { mouse_event(0x0008, 0, 0, 0, UIntPtr.Zero); mouse_event(0x0010, 0, 0, 0, UIntPtr.Zero); }
                        }
                    }
                }
                finally { sessionStop.Cancel(); try { await frames; } catch { } }
            }
            catch (Exception) { /* network/session ended */ }
        }
    }
    [StructLayout(LayoutKind.Sequential)] private struct INPUT { public uint type; public InputUnion data; }
    [StructLayout(LayoutKind.Explicit)] private struct InputUnion { [FieldOffset(0)] public KEYBDINPUT keyboard; }
    [StructLayout(LayoutKind.Sequential)] private struct KEYBDINPUT { public ushort vk, scan; public uint flags, time; public UIntPtr extra; }
    [DllImport("user32.dll", SetLastError = true)] private static extern uint SendInput(uint count, INPUT[] inputs, int size);
    private static void SendUnicode(char character)
    {
        var pair = new [] {
            new INPUT { type=1, data=new InputUnion { keyboard=new KEYBDINPUT { scan=character, flags=0x0004 } } },
            new INPUT { type=1, data=new InputUnion { keyboard=new KEYBDINPUT { scan=character, flags=0x0004 | 0x0002 } } }
        };
        SendInput((uint)pair.Length, pair, Marshal.SizeOf<INPUT>());
    }
    private static void SendVirtualKey(ushort vk)
    {
        var pair = new [] {
            new INPUT { type=1, data=new InputUnion { keyboard=new KEYBDINPUT { vk=vk } } },
            new INPUT { type=1, data=new InputUnion { keyboard=new KEYBDINPUT { vk=vk, flags=0x0002 } } }
        };
        SendInput((uint)pair.Length, pair, Marshal.SizeOf<INPUT>());
    }
    private static void SendKeyPart(ushort key, bool release)
    {
        var data = new[] { new INPUT { type = 1, data = new InputUnion {
            keyboard = new KEYBDINPUT { vk = key, flags = release ? 0x0002u : 0u }
        } } };
        SendInput(1, data, Marshal.SizeOf<INPUT>());
    }
    private static void SendShortcut(ushort key, string modifiers)
    {
        var pressed = new System.Collections.Generic.List<ushort>();
        if (modifiers.Contains("CTRL", StringComparison.Ordinal)) pressed.Add(0x11);
        if (modifiers.Contains("ALT", StringComparison.Ordinal)) pressed.Add(0x12);
        if (modifiers.Contains("SHIFT", StringComparison.Ordinal)) pressed.Add(0x10);
        if (modifiers.Contains("WIN", StringComparison.Ordinal)) pressed.Add(0x5B);
        foreach (var vk in pressed) SendKeyPart(vk, false);
        try { SendVirtualKey(key); }
        finally { for (int i = pressed.Count - 1; i >= 0; i--) SendKeyPart(pressed[i], true); }
    }
    private sealed class StreamSettings { public volatile string Profile = "balanced"; }
    // CopyFromScreen does not include the Windows hardware pointer, so composite
    // the actual cursor onto each JPEG using its screen coordinates and hotspot.
    [StructLayout(LayoutKind.Sequential)]
    private struct CURSORINFO
    {
        public int cbSize;
        public int flags;
        public IntPtr hCursor;
        public System.Drawing.Point ptScreenPos;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct ICONINFO
    {
        [MarshalAs(UnmanagedType.Bool)] public bool fIcon;
        public uint xHotspot, yHotspot;
        public IntPtr hbmMask, hbmColor;
    }
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool GetIconInfo(IntPtr cursor, out ICONINFO info);
    [DllImport("gdi32.dll")] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DeleteObject(IntPtr bitmap);
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool GetCursorInfo(ref CURSORINFO cursor);
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DrawIconEx(IntPtr dc, int x, int y, IntPtr icon,
        int width, int height, uint frameStep, IntPtr brush, uint flags);

    private static void PaintCursor(Graphics graphics, Rectangle desktop)
    {
        var cursor = new CURSORINFO { cbSize = Marshal.SizeOf<CURSORINFO>() };
        if (!GetCursorInfo(ref cursor) || (cursor.flags & 1) == 0 || cursor.hCursor == IntPtr.Zero) return;
        int hotspotX = 0, hotspotY = 0;
        if (GetIconInfo(cursor.hCursor, out var icon))
        {
            hotspotX = checked((int)icon.xHotspot);
            hotspotY = checked((int)icon.yHotspot);
            if (icon.hbmMask != IntPtr.Zero) DeleteObject(icon.hbmMask);
            if (icon.hbmColor != IntPtr.Zero) DeleteObject(icon.hbmColor);
        }
        var dc = graphics.GetHdc();
        try
        {
            DrawIconEx(dc, cursor.ptScreenPos.X - desktop.Left - hotspotX,
                cursor.ptScreenPos.Y - desktop.Top - hotspotY, cursor.hCursor, 0, 0, 0,
                IntPtr.Zero, 0x0003);
        }
        finally { graphics.ReleaseHdc(dc); }
    }
    // Loopback capture records the Windows default playback device, not the microphone.
    // Audio messages share the existing TLS stream with video so no second unauthenticated port is opened.
    private static async Task SendFramesAndAudio(Stream stream, CancellationToken ct, StreamSettings settings)
    {
        using var capture = new WasapiLoopbackCapture();
        var format = capture.WaveFormat;
        var pending = new ConcurrentQueue<byte[]>();
        int queued = 0;
        bool supported = format.Encoding == WaveFormatEncoding.IeeeFloat && format.BitsPerSample == 32
                         || format.Encoding == WaveFormatEncoding.Pcm && format.BitsPerSample == 16;
        if (supported)
        {
            capture.DataAvailable += (sender, args) =>
            {
                int sampleBytes = format.BitsPerSample / 8;
                int samples = args.BytesRecorded / (sampleBytes * format.Channels);
                var pcm = new byte[samples * 2]; // mono PCM16; Android AudioTrack plays at source sample rate
                for (int i = 0; i < samples; i++)
                {
                    float sum = 0f;
                    for (int channel = 0; channel < format.Channels; channel++)
                    {
                        int offset = (i * format.Channels + channel) * sampleBytes;
                        float value = format.BitsPerSample == 32
                            ? BitConverter.ToSingle(args.Buffer, offset)
                            : BitConverter.ToInt16(args.Buffer, offset) / 32768f;
                        sum += value;
                    }
                    short value16 = (short)Math.Clamp((int)(sum / format.Channels * 32767f), -32768, 32767);
                    pcm[i * 2] = (byte)value16;
                    pcm[i * 2 + 1] = (byte)((ushort)value16 >> 8);
                }
                pending.Enqueue(pcm);
                Interlocked.Increment(ref queued);
                while (Volatile.Read(ref queued) > 6 && pending.TryDequeue(out _))
                    Interlocked.Decrement(ref queued);
            };
            capture.StartRecording();
        }
        try
        {
            var encoder = ImageCodecInfo.GetImageEncoders().First(x => x.MimeType == "image/jpeg");
            while (!ct.IsCancellationRequested)
            {
                var clock = Stopwatch.StartNew();
                var profile = settings.Profile;
                int maxLongEdge = profile == "data" ? 960 : profile == "smooth" ? 1280 : 1600;
                int frameInterval = profile == "data" ? 85 : profile == "smooth" ? 33 : 50;
                long quality = profile == "data" ? 43L : profile == "smooth" ? 48L : 58L;
                // Send a bounded amount of buffered audio before the next video frame.
                for (int i = 0; i < 3 && pending.TryDequeue(out var chunk); i++)
                {
                    Interlocked.Decrement(ref queued);
                    await RemoteTransport.SendAsync(stream,
                        new { type = "audio", sampleRate = format.SampleRate, pcm = Convert.ToBase64String(chunk) }, ct);
                }
                var bounds = SystemInformation.VirtualScreen;
                using var bmp = new Bitmap(bounds.Width, bounds.Height);
                using (var graphics = Graphics.FromImage(bmp))
                {
                    graphics.CopyFromScreen(bounds.Left, bounds.Top, 0, 0, bounds.Size);
                    PaintCursor(graphics, bounds);
                }
                double scale = Math.Min(1.0, maxLongEdge / (double)Math.Max(bounds.Width, bounds.Height));
                using var scaled = new Bitmap(bmp, new Size(
                    Math.Max(1, (int)(bounds.Width * scale)), Math.Max(1, (int)(bounds.Height * scale))));
                using var ms = new MemoryStream();
                using var parameters = new EncoderParameters(1);
                parameters.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, quality);
                scaled.Save(ms, encoder, parameters);
                await RemoteTransport.SendAsync(stream, new { type = "frame", jpeg = Convert.ToBase64String(ms.ToArray()) }, ct);
                await Task.Delay(Math.Max(1, frameInterval - (int)clock.ElapsedMilliseconds), ct);
            }
        }
        finally
        {
            if (supported) capture.StopRecording();
        }
    }
    [DllImport("user32.dll")] private static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extraInfo);
    public ValueTask DisposeAsync() { stop.Cancel(); listener.Stop(); certificate.Dispose(); stop.Dispose(); return ValueTask.CompletedTask; }
}
