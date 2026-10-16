using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using MouseEventArgs = System.Windows.Input.MouseEventArgs;
using Button = System.Windows.Controls.Button;
using TextBox = System.Windows.Controls.TextBox;
using Clipboard = System.Windows.Clipboard;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace AZRemoteConnect;

public partial class MainWindow : Window
{
    private LanHost? host;
    private CancellationTokenSource? viewerStop;
    private SslStream? viewer;
    private readonly SemaphoreSlim sendLock = new(1, 1);
    private bool touchpadMode;
    private System.Windows.Point? lastPointer;
    private string? connectedHostKey;
    private string? connectedPin;
    private bool pcMuted;

    private static string TrustPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "AZRemoteConnect", "trusted-phones.json");

    public MainWindow()
    {
        InitializeComponent();
        RemoteDeviceTitle.Text = "Connect your Android phone";
        UpdateMode();
    }

    private static Dictionary<string, string> ReadTrust()
    {
        try
        {
            if (File.Exists(TrustPath))
                return JsonSerializer.Deserialize<Dictionary<string, string>>(File.ReadAllText(TrustPath))
                    ?? new Dictionary<string, string>();
        }
        catch { /* Invalid file: do not silently trust a corrupt entry. */ }
        return new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
    }

    private static void RememberTrust(string key, string pin)
    {
        var known = ReadTrust();
        known[key] = pin;
        Directory.CreateDirectory(Path.GetDirectoryName(TrustPath)!);
        File.WriteAllText(TrustPath, JsonSerializer.Serialize(known, new JsonSerializerOptions { WriteIndented = true }));
    }

    private void RefreshState(bool connected)
    {
        ConnectionStatus.Text = connected ? "●  Connected" : "●  Disconnected";
        ConnectionStatus.Foreground = new SolidColorBrush(connected ?
            System.Windows.Media.Color.FromRgb(88, 255, 151) :
            System.Windows.Media.Color.FromRgb(179, 202, 239));
        RemoteDeviceTitle.Text = connected ? "Android phone" : "Connect your Android phone";
        RemoteDeviceAddress.Text = connected ? RemoteHost.Text.Trim() + " • Remote screen connected" : "Not connected";
        PreviewHint.Text = connected ? (touchpadMode ? "Touchpad: drag to move, click to tap" : "Screen Touch: click on the live preview")
            : "Awaiting approved host connection";
        EmptyScreen.Visibility = connected ? Visibility.Collapsed : Visibility.Visible;
        Connect.IsEnabled = !connected && viewerStop == null;
    }

    private void SetStatus(string message) => Status.Text = "●  " + message;

    private void Header_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ClickCount == 2) { Maximize_Click(sender, new RoutedEventArgs()); return; }
        if (e.LeftButton == MouseButtonState.Pressed) DragMove();
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();
    private void Minimize_Click(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;
    private void Maximize_Click(object sender, RoutedEventArgs e) => WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;

    private void Settings_Click(object sender, RoutedEventArgs e) => ExplainSettings();

    private void Nav_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button b || b.Tag is not string page) return;
        PageTitle.Text = page == "Dashboard" ? "Remote Access Dashboard" : page;
        PageSubtitle.Text = page switch
        {
            "Dashboard" => "Connect to an Android phone and control it from your computer.",
            "Devices" => "Choose a device IP and enter its temporary six-digit pairing code below.",
            "Screen Share" => "The live phone preview appears on the right after the host approves a connection.",
            "Audio" => "PC-to-Android playback exists. Android-to-PC audio is not supported in this build.",
            "Files" => "File transfer is a planned feature and is not enabled in this build.",
            _ => "Trusted-host information and security settings are available."
        };
        if (page == "Settings") ExplainSettings();
        if (page == "Files") ShowInformation("File transfer", "File transfer is not implemented in this release. No files are read or transferred.");
        if (page == "Audio") ShowInformation("Audio support", "Audio from the Android phone to the Windows PC has not been implemented. The existing Windows host can send Windows system audio to a connected Android client.");
        if (page == "Screen Share") ScreenView.Focus();
    }

    private void ExplainSettings()
    {
        ShowInformation("Connection security", "Enter only the six-digit temporary code shown by the Android host. On first contact, AZ RemoteConnect asks you to trust the device's TLS identity. That full identity is remembered in your Windows profile and a later certificate change is blocked.\n\nIMPORTANT: The first connection is trust-on-first-use, which can be intercepted on a hostile network. The code is not a cryptographic replacement for strong peer verification. Use a trusted hotspot or VPN; never forward port 48765 onto the public internet.\n\nTo remove a saved identity, use 'Forget phone trust'.");
    }

    private void ShowInformation(string title, string message) =>
        System.Windows.MessageBox.Show(this, message, title, MessageBoxButton.OK, MessageBoxImage.Information);

    private async void StartHost_Click(object sender, RoutedEventArgs e)
    {
        if (host != null) { SetStatus("This computer is already sharing."); return; }
        try
        {
            host = new LanHost(async () =>
            {
                var decision = await Dispatcher.InvokeAsync(() =>
                    System.Windows.MessageBox.Show(this, "A remote device requests control of this Windows computer. Allow this attended session?", "AZ RemoteConnect — Permission", MessageBoxButton.YesNo, MessageBoxImage.Warning) == MessageBoxResult.Yes);
                return decision;
            });
            host.Start();
            HostDetails.Text = "Temporary six-digit code: " + host.PairCode + "\nPort 48765 • Your approval is required. Android viewer builds that still request certificate verification: " + host.VerificationCode;
            SetStatus("Hosting PC screen. Waiting for an approved peer.");
        }
        catch (Exception ex)
        {
            SetStatus("Cannot start PC hosting: " + ex.Message);
            if (host != null) { await host.DisposeAsync(); host = null; }
        }
    }

    private void CopyCode_Click(object sender, RoutedEventArgs e)
    {
        if (host == null) { SetStatus("Start PC sharing to generate a temporary code."); return; }
        Clipboard.SetText(host.PairCode);
        SetStatus("Temporary PC host code copied.");
    }

    private void TrustReset_Click(object sender, RoutedEventArgs e)
    {
        if (viewerStop != null) { SetStatus("Disconnect before changing trusted hosts."); return; }
        var hostName = RemoteHost.Text.Trim();
        if (!int.TryParse(RemotePort.Text, out var port)) port = 48765;
        var key = hostName + ":" + port;
        var known = ReadTrust();
        if (known.Remove(key))
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(TrustPath)!);
                File.WriteAllText(TrustPath, JsonSerializer.Serialize(known));
                SetStatus("Forgot trust for " + key + ". A new first-use confirmation will be required.");
            }
            catch (Exception ex) { SetStatus("Could not remove trust: " + ex.Message); }
        }
        else SetStatus("No saved trusted certificate for " + key + ".");
    }

    private async void Connect_Click(object sender, RoutedEventArgs e)
    {
        if (viewerStop != null) return;
        string code = RemoteCode.Text.Trim();
        string address = RemoteHost.Text.Trim();
        if (!Regex.IsMatch(code, "^[0-9]{6}$"))
        {
            SetStatus("Enter the exact six-digit temporary pairing code from your phone.");
            return;
        }
        if (string.IsNullOrWhiteSpace(address) || address.Length > 253)
        {
            SetStatus("Enter a valid phone IP address or VPN hostname.");
            return;
        }
        if (!int.TryParse(RemotePort.Text.Trim(), out var port) || port < 1 || port > 65535)
        {
            SetStatus("Invalid port. Default is 48765.");
            return;
        }

        connectedHostKey = address + ":" + port;
        var known = ReadTrust();
        known.TryGetValue(connectedHostKey, out var trustedPin);
        string? seenPin = null;
        viewerStop = new CancellationTokenSource();
        var token = viewerStop.Token;
        var connectionPhase = "TCP";
        Connect.IsEnabled = false;

        try
        {
            SetStatus("Connecting to " + connectedHostKey + "...");
            var client = new TcpClient();
            await client.ConnectAsync(address, port, token);
            connectionPhase = "TLS authentication";
            SetStatus("Reached phone. Establishing secure channel...");
            viewer = new SslStream(client.GetStream(), false, (_, cert, _, _) =>
            {
                if (cert == null) return false;
                seenPin = RemoteTransport.Fingerprint(cert);
                return trustedPin == null || string.Equals(seenPin, trustedPin, StringComparison.Ordinal);
            });
            await viewer.AuthenticateAsClientAsync(
                new SslClientAuthenticationOptions
                {
                    TargetHost = "AZRemoteConnect-Temporary",
                    EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13
                }, token);

            if (seenPin == null) throw new AuthenticationException("Host did not present a TLS identity.");
            connectionPhase = "First-time trust";
            if (trustedPin == null)
            {
                var choice = System.Windows.MessageBox.Show(this,
                    "This is the first connection to " + connectedHostKey + ".\n\n" +
                    "There is no saved device identity yet. If the address is untrusted, someone on the network could impersonate your phone and capture the six-digit code.\n\n" +
                    "Only continue on your trusted hotspot or private VPN. Once your phone approves the connection, its TLS identity will be remembered and future changes blocked.\n\nTrust this device for a first connection?",
                    "First connection — verify your network", MessageBoxButton.YesNo, MessageBoxImage.Warning);
                if (choice != MessageBoxResult.Yes) throw new AuthenticationException("You declined to trust the first connection.");
            }
            connectionPhase = "Pairing and phone approval";
            SetStatus("Secure channel opened. Waiting for phone approval...");
            await RemoteTransport.SendAsync(viewer, new { type = "pair", code }, token);
            using (var accepted = await RemoteTransport.ReadAsync(viewer, token))
            {
                if (RemoteTransport.Field(accepted, "type") != "approved")
                {
                    var reason = RemoteTransport.Field(accepted, "message");
                    throw new InvalidDataException(string.IsNullOrWhiteSpace(reason) ? "The host did not approve the session." : reason);
                }
            }

            if (trustedPin == null)
            {
                RememberTrust(connectedHostKey, seenPin);
            }
            connectedPin = seenPin;
            connectionPhase = "Screen streaming";
            SetStatus("Connected to " + address + " • Phone session approved");
            RefreshState(true);
            viewer.ReadTimeout = Timeout.Infinite;
            await ReceiveFrames(viewer, token);
        }
        catch (AuthenticationException ex)
        {
            SetStatus("Certificate or trust error: " + ex.Message + ". If the Android app was reinstalled, forget trusted phone and verify again on a trusted network.");
        }
        catch (OperationCanceledException) { SetStatus("Connection cancelled."); }
        catch (EndOfStreamException)
        {
            SetStatus(connectionPhase == "TLS authentication"
                ? "Phone closed the secure handshake. On Android tap Share phone, allow screen capture, wait for READY, then Refresh code. Check Last session error for TLS details."
                : "Phone closed during " + connectionPhase + ". Open its sharing notification and Refresh host code. Re-enter the new 6-digit code.");
        }
        catch (IOException ex)
        {
            if (ex.Message.Contains("EOF", StringComparison.OrdinalIgnoreCase) ||
                ex.Message.Contains("0 bytes", StringComparison.OrdinalIgnoreCase))
                SetStatus("Phone closed the connection during " + connectionPhase +
                    ". Open AZ RemoteConnect on the phone, tap Refresh phone host code, and check Host status / Last session error. Restart sharing if required.");
            else SetStatus(connectionPhase + " failed: " + ex.Message);
        }
        catch (Exception ex) { SetStatus("Could not connect: " + ex.Message); }
        finally
        {
            viewer?.Dispose();
            viewer = null;
            viewerStop?.Dispose();
            viewerStop = null;
            connectedPin = null;
            connectedHostKey = null;
            ScreenView.Source = null;
            RefreshState(false);
        }
    }

    private async Task ReceiveFrames(SslStream stream, CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            using var packet = await RemoteTransport.ReadAsync(stream, token);
            if (RemoteTransport.Field(packet, "type") != "frame") continue;
            var jpeg = Convert.FromBase64String(RemoteTransport.Field(packet, "jpeg"));
            using var ms = new MemoryStream(jpeg);
            var image = new BitmapImage();
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.StreamSource = ms;
            image.EndInit();
            image.Freeze();
            ScreenView.Source = image;
        }
    }

    private void UpdateMode()
    {
        ScreenTouchButton.Background = new SolidColorBrush(touchpadMode ?
            System.Windows.Media.Color.FromRgb(10, 55, 112) : System.Windows.Media.Color.FromRgb(4, 100, 222));
        TouchpadButton.Background = new SolidColorBrush(touchpadMode ?
            System.Windows.Media.Color.FromRgb(4, 100, 222) : System.Windows.Media.Color.FromRgb(10, 55, 112));
        PreviewHint.Text = touchpadMode ? "Touchpad: drag and release to click" : "Screen Touch: click where you want to tap";
    }
    private void TouchMode_Click(object sender, RoutedEventArgs e)
    {
        touchpadMode = false; UpdateMode();
        SetStatus("Screen Touch selected. Android host must have explicitly enabled remote-touch access.");
    }
    private void TouchpadMode_Click(object sender, RoutedEventArgs e)
    {
        touchpadMode = true; UpdateMode();
        SetStatus("Touchpad selected. Remote gestures require the Full Android edition and consent.");
    }

    private async Task SendRemote(object packet)
    {
        var stream = viewer;
        var cts = viewerStop;
        if (stream == null || cts == null) return;
        if (!await sendLock.WaitAsync(0)) return;
        try { await RemoteTransport.SendAsync(stream, packet, cts.Token); }
        catch (Exception ex) { SetStatus("Remote control unavailable: " + ex.Message); }
        finally { sendLock.Release(); }
    }

    private bool TryPoint(System.Windows.Controls.Image view, MouseEventArgs e, out double x, out double y)
    {
        var p = e.GetPosition(view);
        x = 0; y = 0;
        if (view.Source is not BitmapSource source || view.ActualWidth <= 0 || view.ActualHeight <= 0) return false;
        // WPF Image.Stretch=Uniform preserves the remote display ratio, with letterboxing.
        var scale = Math.Min(view.ActualWidth / source.PixelWidth, view.ActualHeight / source.PixelHeight);
        var drawnWidth = source.PixelWidth * scale;
        var drawnHeight = source.PixelHeight * scale;
        x = (p.X - (view.ActualWidth - drawnWidth) / 2d) / drawnWidth;
        y = (p.Y - (view.ActualHeight - drawnHeight) / 2d) / drawnHeight;
        return x is >= 0 and <= 1 && y is >= 0 and <= 1;
    }

    private async void ScreenView_MouseDown(object sender, MouseButtonEventArgs e)
    {
        if (viewer == null || e.ChangedButton != MouseButton.Left) return;
        var view = sender as System.Windows.Controls.Image ?? ScreenView;
        view.Focus();
        var p = e.GetPosition(view);
        lastPointer = p;
        if (!touchpadMode && TryPoint(view, e, out var x, out var y))
            await SendRemote(new { type = "mouse", x, y, action = "down" });
        view.CaptureMouse();
    }
    private async void ScreenView_MouseMove(object sender, MouseEventArgs e)
    {
        if (viewer == null || e.LeftButton != MouseButtonState.Pressed || !lastPointer.HasValue) return;
        var view = sender as System.Windows.Controls.Image ?? ScreenView;
        var now = e.GetPosition(view);
        if (touchpadMode)
        {
            var dx = now.X - lastPointer.Value.X;
            var dy = now.Y - lastPointer.Value.Y;
            if (Math.Abs(dx) + Math.Abs(dy) > 0.5)
                await SendRemote(new { type = "relative", dx = dx * 1.4, dy = dy * 1.4 });
        }
        else if (TryPoint(view, e, out var x, out var y))
            await SendRemote(new { type = "mouse", x, y, action = "move" });
        lastPointer = now;
    }
    private async void ScreenView_MouseUp(object sender, MouseButtonEventArgs e)
    {
        if (e.ChangedButton != MouseButton.Left) return;
        var view = sender as System.Windows.Controls.Image ?? ScreenView;
        view.ReleaseMouseCapture();
        if (viewer == null) { lastPointer = null; return; }
        if (touchpadMode)
            await SendRemote(new { type = "click", button = "left" });
        else if (TryPoint(view, e, out var x, out var y))
            await SendRemote(new { type = "mouse", x, y, action = "up" });
        lastPointer = null;
    }
    private void ScreenView_MouseWheel(object sender, MouseWheelEventArgs e)
    {
        SetStatus("Remote wheel scrolling requires support from the Android host. This version does not inject Android scroll gestures.");
    }

    private Window? fullscreenWindow;
    private void FullScreen_Click(object sender, RoutedEventArgs e)
    {
        if (viewer == null || ScreenView.Source == null)
        {
            SetStatus("Connect to a phone to open its full-screen live view.");
            return;
        }
        if (fullscreenWindow != null) { fullscreenWindow.Activate(); return; }
        var image = new System.Windows.Controls.Image { Stretch = Stretch.Uniform };
        image.SetBinding(System.Windows.Controls.Image.SourceProperty, new System.Windows.Data.Binding("Source") { Source = ScreenView });
        fullscreenWindow = new Window
        {
            Title = "AZ RemoteConnect — Phone full screen",
            WindowStyle = WindowStyle.None,
            WindowState = WindowState.Maximized,
            Background = System.Windows.Media.Brushes.Black,
            Owner = this,
            Content = image
        };
        image.MouseDown += (source, ev) => {
            if (ev.ClickCount == 2) { fullscreenWindow?.Close(); return; }
            ScreenView_MouseDown(source, ev);
        };
        image.MouseMove += ScreenView_MouseMove;
        image.MouseUp += ScreenView_MouseUp;
        fullscreenWindow.KeyDown += (_, ev) => { if (ev.Key == Key.Escape) fullscreenWindow?.Close(); };
        fullscreenWindow.Closed += (_, _) => fullscreenWindow = null;
        fullscreenWindow.Show();
        SetStatus("Full-screen preview open; Esc or double click closes.");
    }

    private void RemoteAudio_Click(object sender, RoutedEventArgs e) =>
        ShowInformation("Android audio streaming", "The current Android host streams its screen but not Android system audio to Windows. This tile is informational, not an enabled audio transfer.");
    private void FileTransfer_Click(object sender, RoutedEventArgs e) =>
        ShowInformation("File transfer", "File transfer has not been implemented. The app does not browse, send, or download files from connected devices.");
    private void MutePcAudio_Click(object sender, RoutedEventArgs e) =>
        ShowInformation("PC audio", "Muting PC audio is not implemented as a remote-session feature. Use the Windows volume controls. Android-to-Windows audio is not currently available.");

    private async void SendKeyboard_Click(object sender, RoutedEventArgs e)
    {
        if (viewer == null) { SetStatus("Connect first. Keyboard input requires remote touch permission on the Android phone."); return; }
        var field = new TextBox { Margin = new Thickness(12), MinWidth = 260, Height = 42, FontSize = 16 };
        var dialog = new Window
        {
            Title = "Send text to phone",
            Owner = this, Width = 410, Height = 170,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = System.Windows.Media.Brushes.White,
            Content = new StackPanel
            {
                Children =
                {
                    new TextBlock { Text = "Replace text in the focused Android input field:", Margin = new Thickness(12, 13, 12, 0), Foreground = System.Windows.Media.Brushes.Black, FontSize = 13 },
                    field
                }
            }
        };
        var panel = (StackPanel)dialog.Content;
        var send = new Button { Content = "Send text", HorizontalAlignment = System.Windows.HorizontalAlignment.Right, Width = 110, Height = 30, Margin = new Thickness(0, 0, 14, 0) };
        panel.Children.Add(send);
        send.Click += (_, _) => { dialog.DialogResult = true; };
        if (dialog.ShowDialog() == true && field.Text.Length is > 0 and <= 200)
        {
            await SendRemote(new { type = "text", value = field.Text });
            SetStatus("Text command sent. Android requires the Full edition, Accessibility consent, and a focused input field.");
        }
    }

    private async void Disconnect_Click(object sender, RoutedEventArgs e)
    {
        viewerStop?.Cancel();
        viewer?.Dispose();
        viewer = null;
        if (host != null) { await host.DisposeAsync(); host = null; }
        HostDetails.Text = "PC hosting is off. Each session uses a new six-digit pairing code.";
        SetStatus("Disconnected.");
        ScreenView.Source = null;
        RefreshState(false);
    }

    protected override async void OnClosed(EventArgs e)
    {
        fullscreenWindow?.Close();
        viewerStop?.Cancel();
        viewer?.Dispose();
        if (host != null) await host.DisposeAsync();
        base.OnClosed(e);
    }
}
