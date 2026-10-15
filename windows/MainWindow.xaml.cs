using System;
using System.IO;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media.Imaging;

namespace AZRemoteConnect;

public partial class MainWindow : Window
{
    private LanHost? host;
    private CancellationTokenSource? viewerStop;
    private SslStream? viewer;
    private readonly SemaphoreSlim sendLock = new(1, 1);
    public MainWindow() { InitializeComponent(); }
    private async void StartHost_Click(object sender, RoutedEventArgs e)
    {
        if (host != null) return;
        try
        {
            host = new LanHost(() => Dispatcher.InvokeAsync(() =>
                MessageBox.Show(this, "A remote device wants to control this computer. Allow?", "Authorize remote access", MessageBoxButton.YesNo, MessageBoxImage.Warning) == MessageBoxResult.Yes).Task);
            host.Start();
            HostDetails.Text = $"Listening on port 48765\nPairing code: {host.PairCode}\nTLS SHA-256 fingerprint:\n{host.Fingerprint}\nShare these securely with the controller.";
            Status.Text = "Hosting on the LAN; waiting for approval.";
        }
        catch (Exception ex) { Status.Text = ex.Message; if (host != null) { await host.DisposeAsync(); host = null; } }
    }
    private async void Connect_Click(object sender, RoutedEventArgs e)
    {
        if (viewerStop != null) return;
        var expected = RemoteFingerprint.Text.Trim().Replace(":", "").Replace(" ", "").ToUpperInvariant();
        if (expected.Length != 64 || !System.Text.RegularExpressions.Regex.IsMatch(expected, "^[0-9A-F]{64}$"))
        { Status.Text = "Enter the host's full SHA-256 TLS fingerprint."; return; }
        if (!int.TryParse(RemotePort.Text, out int port) || port is < 1 or > 65535) { Status.Text = "Invalid port"; return; }
        if (!System.Text.RegularExpressions.Regex.IsMatch(RemoteCode.Text.Trim(), "^[0-9]{6}$")) { Status.Text = "Invalid pairing code"; return; }
        viewerStop = new CancellationTokenSource();
        try
        {
            var client = new TcpClient();
            await client.ConnectAsync(RemoteHost.Text.Trim(), port, viewerStop.Token);
            viewer = new SslStream(client.GetStream(), false, (_, cert, _, _) => cert != null && RemoteTransport.Fingerprint(cert) == expected);
            await viewer.AuthenticateAsClientAsync(new SslClientAuthenticationOptions { TargetHost = "AZRemoteConnect-Temporary", EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13 }, viewerStop.Token);
            await RemoteTransport.SendAsync(viewer, new { type = "pair", code = RemoteCode.Text.Trim() }, viewerStop.Token);
            using (var accepted = await RemoteTransport.ReadAsync(viewer, viewerStop.Token))
                if (RemoteTransport.Field(accepted, "type") != "approved") throw new InvalidDataException("Connection not approved");
            Status.Text = "Session approved — remote screen active.";
            EmptyScreen.Visibility = Visibility.Collapsed;
            await ReceiveFrames(viewer, viewerStop.Token);
        }
        catch (Exception ex) { Status.Text = $"Disconnected: {ex.Message}"; }
        finally { viewer?.Dispose(); viewer = null; viewerStop?.Dispose(); viewerStop = null; EmptyScreen.Visibility = Visibility.Visible; }
    }
    private async Task ReceiveFrames(SslStream stream, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            using var packet = await RemoteTransport.ReadAsync(stream, ct);
            if (RemoteTransport.Field(packet, "type") != "frame") continue;
            var jpg = Convert.FromBase64String(RemoteTransport.Field(packet, "jpeg"));
            using var memory = new MemoryStream(jpg);
            var bitmap = new BitmapImage();
            bitmap.BeginInit(); bitmap.CacheOption = BitmapCacheOption.OnLoad; bitmap.StreamSource = memory; bitmap.EndInit(); bitmap.Freeze();
            ScreenView.Source = bitmap;
        }
    }
    private async void ScreenView_MouseMove(object sender, System.Windows.Input.MouseEventArgs e) { if (e.LeftButton == MouseButtonState.Pressed) await SendMouse("move",e); }
    private async void ScreenView_MouseDown(object sender, MouseButtonEventArgs e) { if(e.ChangedButton == MouseButton.Left) await SendMouse("down",e); }
    private async void ScreenView_MouseUp(object sender, MouseButtonEventArgs e) { if(e.ChangedButton == MouseButton.Left) await SendMouse("up",e); }
    private async Task SendMouse(string action, System.Windows.Input.MouseEventArgs e)
    {
        if (viewer == null || viewerStop == null) return;
        var p = e.GetPosition(ScreenView);
        if (ScreenView.ActualWidth <= 0 || ScreenView.ActualHeight <= 0) return;
        double x = p.X / ScreenView.ActualWidth, y = p.Y / ScreenView.ActualHeight;
        if (x is < 0 or > 1 || y is < 0 or > 1) return;
        if (!await sendLock.WaitAsync(0)) return;
        try { await RemoteTransport.SendAsync(viewer,new { type="mouse", x, y, action },viewerStop.Token); } catch { }
        finally { sendLock.Release(); }
    }
    private async void Stop_Click(object sender, RoutedEventArgs e)
    {
        viewerStop?.Cancel(); viewer?.Dispose(); viewer = null;
        if(host != null) { await host.DisposeAsync(); host=null; }
        HostDetails.Text = ""; Status.Text = "Stopped";
    }
    protected override async void OnClosed(EventArgs e)
    {
        viewerStop?.Cancel(); viewer?.Dispose();
        if (host != null) await host.DisposeAsync();
        base.OnClosed(e);
    }
}
