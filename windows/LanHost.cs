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
                var frames = SendFrames(tls, sessionStop.Token);
                try
                {
                    while (!sessionStop.IsCancellationRequested)
                    {
                        using var command = await RemoteTransport.ReadAsync(tls, sessionStop.Token);
                        if (RemoteTransport.Field(command, "type") == "mouse")
                        {
                            if (!double.TryParse(RemoteTransport.Field(command, "x"), out var x) || !double.TryParse(RemoteTransport.Field(command, "y"), out var y)) continue;
                            if (x < 0 || x > 1 || y < 0 || y > 1) continue;
                            var bounds = SystemInformation.VirtualScreen;
                            Cursor.Position = new Point(bounds.Left + (int)(x * (bounds.Width-1)), bounds.Top + (int)(y * (bounds.Height-1)));
                            var action = RemoteTransport.Field(command, "action");
                            if (action == "down") mouse_event(0x0002, 0, 0, 0, UIntPtr.Zero);
                            if (action == "up") mouse_event(0x0004, 0, 0, 0, UIntPtr.Zero);
                        }
                    }
                }
                finally { sessionStop.Cancel(); try { await frames; } catch { } }
            }
            catch (Exception) { /* network/session ended */ }
        }
    }
    private static async Task SendFrames(Stream stream, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            var bounds = SystemInformation.VirtualScreen;
            using var bmp = new Bitmap(bounds.Width, bounds.Height);
            using (var graphics = Graphics.FromImage(bmp)) graphics.CopyFromScreen(bounds.Left, bounds.Top, 0, 0, bounds.Size);
            using var ms = new MemoryStream();
            var encoder = ImageCodecInfo.GetImageEncoders().First(x => x.MimeType == "image/jpeg");
            using var parameters = new EncoderParameters(1);
            parameters.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 55L);
            bmp.Save(ms, encoder, parameters);
            await RemoteTransport.SendAsync(stream, new { type = "frame", jpeg = Convert.ToBase64String(ms.ToArray()) }, ct);
            await Task.Delay(250, ct);
        }
    }
    [DllImport("user32.dll")] private static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extraInfo);
    public ValueTask DisposeAsync() { stop.Cancel(); listener.Stop(); certificate.Dispose(); stop.Dispose(); return ValueTask.CompletedTask; }
}
