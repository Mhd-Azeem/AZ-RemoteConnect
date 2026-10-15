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
    public string VerificationCode => Fingerprint[..12];
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
                var frames = SendFramesAndAudio(tls, sessionStop.Token);
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
                            ushort vk = key switch { "BACKSPACE" => 0x08, "ENTER" => 0x0D, "TAB" => 0x09, "ESC" => 0x1B, "DELETE" => 0x2E, "LEFT" => 0x25, "UP" => 0x26, "RIGHT" => 0x27, "DOWN" => 0x28, _ => (ushort)0 };
                            if (vk != 0) SendVirtualKey(vk);
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
                            if (button == "left") { mouse_event(0x0002,0,0,0,UIntPtr.Zero); mouse_event(0x0004,0,0,0,UIntPtr.Zero); }
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
    // Loopback capture records the Windows default playback device, not the microphone.
    // Audio messages share the existing TLS stream with video so no second unauthenticated port is opened.
    private static async Task SendFramesAndAudio(Stream stream, CancellationToken ct)
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
                    graphics.CopyFromScreen(bounds.Left, bounds.Top, 0, 0, bounds.Size);
                using var ms = new MemoryStream();
                using var parameters = new EncoderParameters(1);
                parameters.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 50L);
                bmp.Save(ms, encoder, parameters);
                await RemoteTransport.SendAsync(stream, new { type = "frame", jpeg = Convert.ToBase64String(ms.ToArray()) }, ct);
                await Task.Delay(16, ct); // 60 FPS target only; actual performance depends on system/network
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
