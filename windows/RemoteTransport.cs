using System;
using System.Buffers.Binary;
using System.IO;
using System.Linq;
using System.Net.Security;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace AZRemoteConnect;

internal static class RemoteTransport
{
    public static async Task SendAsync(Stream stream, object payload, CancellationToken ct)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(payload);
        if (bytes.Length > 8 * 1024 * 1024) throw new InvalidDataException("Frame too large");
        var prefix = new byte[4];
        BinaryPrimitives.WriteInt32BigEndian(prefix, bytes.Length);
        await stream.WriteAsync(prefix, ct);
        await stream.WriteAsync(bytes, ct);
        await stream.FlushAsync(ct);
    }
    public static async Task<JsonDocument> ReadAsync(Stream stream, CancellationToken ct)
    {
        byte[] prefix = new byte[4];
        await stream.ReadExactlyAsync(prefix, ct);
        int length = BinaryPrimitives.ReadInt32BigEndian(prefix);
        if (length <= 0 || length > 8 * 1024 * 1024) throw new InvalidDataException("Invalid frame size");
        byte[] bytes = new byte[length];
        await stream.ReadExactlyAsync(bytes, ct);
        return JsonDocument.Parse(bytes);
    }
    public static string Field(JsonDocument obj, string field) => obj.RootElement.TryGetProperty(field, out var v) ? v.ToString() : "";
    // The first prototype generated a new certificate at every launch, breaking trusted
    // device reconnections. Persist a non-exportable private key in the Windows user store.
    // The certificate expires after three years, when the user must explicitly re-trust it.
    public static X509Certificate2 MakeCertificate()
    {
        const string subject = "CN=AZRemoteConnect-Host-v2";
        using var store = new X509Store(StoreName.My, StoreLocation.CurrentUser);
        store.Open(OpenFlags.ReadWrite);
        var previous = store.Certificates
            .Find(X509FindType.FindBySubjectDistinguishedName, subject, false)
            .OfType<X509Certificate2>()
            .FirstOrDefault(cert => cert.HasPrivateKey && cert.NotAfter > DateTime.UtcNow.AddDays(30));
        if (previous != null) return new X509Certificate2(previous);

        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest(subject, key, HashAlgorithmName.SHA256);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
            new OidCollection { new Oid("1.3.6.1.5.5.7.3.1") }, false));
        using var generated = request.CreateSelfSigned(
            DateTimeOffset.UtcNow.AddMinutes(-5), DateTimeOffset.UtcNow.AddYears(3));
        using var imported = new X509Certificate2(generated.Export(X509ContentType.Pfx),
            (string?)null, X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.PersistKeySet);
        store.Add(imported);
        return new X509Certificate2(imported);
    }
    public static string Fingerprint(X509Certificate cert) => Convert.ToHexString(SHA256.HashData(cert.GetRawCertData()));
}
