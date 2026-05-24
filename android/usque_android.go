package usqueandroid

import (
	"context"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/obrige/usque/api"
	"github.com/obrige/usque/config"
	"github.com/obrige/usque/internal"
	"github.com/obrige/usque/models"
	"github.com/things-go/go-socks5"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

type PacketFlow interface{ WritePacket(data []byte) }
type VpnStateCallback interface {
	OnConnected()
	OnDisconnected(reason string)
	OnError(message string)
}
type TunnelStats struct {
	RxBytes uint64
	TxBytes uint64
}
type tunnelState struct {
	mu        sync.Mutex
	running   bool
	cancel    context.CancelFunc
	inputChan chan []byte
	callback  VpnStateCallback
}

var state = &tunnelState{}
var stats = &TunnelStats{}
var (
	customSNI           = "cdnjs.cloudflare.com"
	customEndpointV4    = ""
	customEndpointV6    = ""
	customPrivateKey    = ""
	customPubKey        = ""
	customJWT           = ""
	customLicense       = ""
	customToken         = ""
	customAccountID     = ""
	useIPv6             = false
	customModel         = "PC"
	customLocale        = "en_US"
	customUserAgent     = ""
	customClientVersion = ""
	useZeroTier         = false
)
var (
	proxyRunning  bool
	proxyCancel   context.CancelFunc
	proxyListener net.Listener
	proxyMu       sync.Mutex
)

// ─── DEFAULT ENDPOINT PRESETS ───
var DefaultEndpointV4Options = []string{
	"162.159.198.2:443",
	"162.159.198.1:443",
	"162.159.198.0:443",
	"162.159.198.2:8443",
	"162.159.198.1:8443",
	"162.159.198.0:8443",
}
var DefaultEndpointV6Options = []string{
	"[2606:4700:103::2]:443",
	"[2606:4700:103::2]:8443",
	"[2606:4700:103::1]:443",
	"[2606:4700:103::1]:8443",
}

func GetDefaultEndpointV4Options() string {
	b, _ := json.Marshal(DefaultEndpointV4Options)
	return string(b)
}
func GetDefaultEndpointV6Options() string {
	b, _ := json.Marshal(DefaultEndpointV6Options)
	return string(b)
}

// Register — EndpointV4/V6 are NOT auto-filled from server; user provides via presets.
func Register(configPath string, deviceName string) string {
	if err := config.LoadConfig(configPath); err == nil {
		return ""
	}
	if customLicense != "" && customToken != "" && customAccountID != "" {
		privKey, pubKey, err := internal.GenerateEcKeyPair()
		if err != nil {
			return fmt.Sprintf("keygen: %v", err)
		}
		updatedAccountData, apiErr, err := api.EnrollKey(models.AccountData{
			Token: customToken, ID: customAccountID,
		}, pubKey, deviceName)
		if err != nil {
			if apiErr != nil {
				return fmt.Sprintf("enroll: %v (API: %s)", err, apiErr.ErrorsAsString("; "))
			}
			return fmt.Sprintf("enroll: %v", err)
		}
		epk := updatedAccountData.Config.Peers[0].PublicKey
		if customPubKey != "" {
			epk = customPubKey
		}
		config.AppConfig = config.Config{
			PrivateKey:     base64.StdEncoding.EncodeToString(privKey),
			EndpointV4:     customEndpointV4,
			EndpointV6:     customEndpointV6,
			EndpointPubKey: epk,
			License:        customLicense,
			ID:             customAccountID,
			AccessToken:    customToken,
			IPv4:           updatedAccountData.Config.Interface.Addresses.V4,
			IPv6:           updatedAccountData.Config.Interface.Addresses.V6,
		}
		if err := config.AppConfig.SaveConfig(configPath); err != nil {
			return fmt.Sprintf("save: %v", err)
		}
		return ""
	}
	mdl := customModel
	if mdl == "" {
		mdl = internal.DefaultModel
	}
	loc := customLocale
	if loc == "" {
		loc = internal.DefaultLocale
	}
	accountData, err := api.Register(mdl, loc, customJWT, true)
	if err != nil {
		return fmt.Sprintf("register: %v", err)
	}
	var privKey, pubKey []byte
	if customPrivateKey != "" {
		privKey, err = base64.StdEncoding.DecodeString(customPrivateKey)
		if err != nil {
			return fmt.Sprintf("decode key: %v", err)
		}
		ecPrivKey, err := config.ParsePrivateKey(privKey)
		if err != nil {
			return fmt.Sprintf("parse key: %v", err)
		}
		pubKeyBytes, err := x509.MarshalPKIXPublicKey(&ecPrivKey.PublicKey)
		if err != nil {
			return fmt.Sprintf("marshal pub: %v", err)
		}
		pubKey = pubKeyBytes
	} else {
		privKey, pubKey, err = internal.GenerateEcKeyPair()
		if err != nil {
			return fmt.Sprintf("keygen: %v", err)
		}
	}
	updatedAccountData, apiErr, err := api.EnrollKey(accountData, pubKey, deviceName)
	if err != nil {
		if apiErr != nil {
			return fmt.Sprintf("enroll: %v (API: %s)", err, apiErr.ErrorsAsString("; "))
		}
		return fmt.Sprintf("enroll: %v", err)
	}
	epk := updatedAccountData.Config.Peers[0].PublicKey
	if customPubKey != "" {
		epk = customPubKey
	}
	config.AppConfig = config.Config{
		PrivateKey:     base64.StdEncoding.EncodeToString(privKey),
		EndpointV4:     customEndpointV4,
		EndpointV6:     customEndpointV6,
		EndpointPubKey: epk,
		License:        updatedAccountData.Account.License,
		ID:             updatedAccountData.ID,
		AccessToken:    accountData.Token,
		IPv4:           updatedAccountData.Config.Interface.Addresses.V4,
		IPv6:           updatedAccountData.Config.Interface.Addresses.V6,
	}
	if err := config.AppConfig.SaveConfig(configPath); err != nil {
		return fmt.Sprintf("save: %v", err)
	}
	return ""
}

func GetAccountInfo(configPath string) string {
	if err := config.LoadConfig(configPath); err != nil {
		return "{}"
	}
	info := map[string]interface{}{
		"id":      config.AppConfig.ID,
		"license": config.AppConfig.License,
		"ipv4":    config.AppConfig.IPv4,
		"ipv6":    config.AppConfig.IPv6,
	}
	b, _ := json.Marshal(info)
	return string(b)
}

func ReEnroll(configPath, deviceName string, regenKey bool) string {
	if err := config.LoadConfig(configPath); err != nil {
		return fmt.Sprintf("no config: %v", err)
	}
	accountData := models.AccountData{
		Token: config.AppConfig.AccessToken,
		ID:    config.AppConfig.ID,
	}
	var privKeyBytes, publicKey []byte
	var err error
	if regenKey {
		privKeyBytes, publicKey, err = internal.GenerateEcKeyPair()
		if err != nil {
			return fmt.Sprintf("keygen: %v", err)
		}
	} else {
		privKey, e := config.AppConfig.GetEcPrivateKey()
		if e != nil {
			return fmt.Sprintf("get key: %v", e)
		}
		publicKey, err = x509.MarshalPKIXPublicKey(&privKey.PublicKey)
		if err != nil {
			return fmt.Sprintf("marshal: %v", err)
		}
		privKeyBytes, err = x509.MarshalECPrivateKey(privKey)
		if err != nil {
			return fmt.Sprintf("marshal priv: %v", err)
		}
	}
	updated, apiErr, err := api.EnrollKey(accountData, publicKey, deviceName)
	if err != nil {
		if apiErr != nil {
			return fmt.Sprintf("enroll: %v (API: %s)", err, apiErr.ErrorsAsString("; "))
		}
		return fmt.Sprintf("enroll: %v", err)
	}
	epk := updated.Config.Peers[0].PublicKey
	if customPubKey != "" {
		epk = customPubKey
	}
	config.AppConfig = config.Config{
		PrivateKey:     base64.StdEncoding.EncodeToString(privKeyBytes),
		EndpointV4:     config.AppConfig.EndpointV4,
		EndpointV6:     config.AppConfig.EndpointV6,
		EndpointPubKey: epk,
		License:        updated.Account.License,
		ID:             updated.ID,
		AccessToken:    accountData.Token,
		IPv4:           updated.Config.Interface.Addresses.V4,
		IPv6:           updated.Config.Interface.Addresses.V6,
	}
	if err := config.AppConfig.SaveConfig(configPath); err != nil {
		return fmt.Sprintf("save: %v", err)
	}
	return ""
}

func GetAvailablePorts(configPath string) string {
	return "443,8443,2053,2083,2087,2096"
}

func SetModel(m string)          { customModel = m }
func GetModel() string           { return customModel }
func SetLocale(l string)         { customLocale = l }
func GetLocale() string          { return customLocale }
func SetUserAgent(ua string)     { customUserAgent = ua; if ua != "" { internal.Headers["User-Agent"] = ua } }
func GetUserAgent() string       { return internal.Headers["User-Agent"] }
func SetClientVersion(v string)  { customClientVersion = v; if v != "" { internal.Headers["CF-Client-Version"] = v } }
func GetClientVersion() string   { return internal.Headers["CF-Client-Version"] }
func SetUseZeroTier(b bool)      { useZeroTier = b; if b { customSNI = internal.ZeroTierSNI } else { customSNI = "cdnjs.cloudflare.com" } }
func GetUseZeroTier() bool       { return useZeroTier }
func GetCFProxyURL(string) string { return "" }

// StartProxy — prefers customEndpoints, parses host:port, falls back to first preset.
func StartProxy(configPath, mode, bindAddr string, port int, username, password string) string {
	proxyMu.Lock()
	defer proxyMu.Unlock()
	if proxyRunning {
		return "proxy already running"
	}
	if err := config.LoadConfig(configPath); err != nil {
		return fmt.Sprintf("config: %v", err)
	}
	privKey, err := config.AppConfig.GetEcPrivateKey()
	if err != nil {
		return fmt.Sprintf("privkey: %v", err)
	}
	peerPubKey, err := config.AppConfig.GetEcEndpointPublicKey()
	if err != nil {
		return fmt.Sprintf("pubkey: %v", err)
	}
	cert, err := internal.GenerateCert(privKey, &privKey.PublicKey)
	if err != nil {
		return fmt.Sprintf("cert: %v", err)
	}
	sni := customSNI
	if sni == "" {
		sni = internal.ConnectSNI
	}
	tlsCfg, err := api.PrepareTlsConfig(privKey, peerPubKey, cert, sni)
	if err != nil {
		return fmt.Sprintf("tls: %v", err)
	}
	var localAddrs []netip.Addr
	if v4, e := netip.ParseAddr(config.AppConfig.IPv4); e == nil {
		localAddrs = append(localAddrs, v4)
	}
	if v6, e := netip.ParseAddr(config.AppConfig.IPv6); e == nil {
		localAddrs = append(localAddrs, v6)
	}
	dnsAddrs := []netip.Addr{netip.MustParseAddr("1.1.1.1"), netip.MustParseAddr("8.8.8.8")}
	tunDev, tunNet, err := netstack.CreateNetTUN(localAddrs, dnsAddrs, 1280)
	if err != nil {
		return fmt.Sprintf("netstack: %v", err)
	}

	epStr := ""
	if useIPv6 && customEndpointV6 != "" {
		epStr = customEndpointV6
	} else if customEndpointV4 != "" {
		epStr = customEndpointV4
	} else if useIPv6 && config.AppConfig.EndpointV6 != "" {
		epStr = config.AppConfig.EndpointV6
	} else if config.AppConfig.EndpointV4 != "" {
		epStr = config.AppConfig.EndpointV4
	} else {
		epStr = DefaultEndpointV4Options[0]
	}
	host, epPort, err := parseEndpoint(epStr)
	if err != nil {
	    return fmt.Sprintf("endpoint parse: %v", err)
	}
	endpoint := &net.UDPAddr{IP: net.ParseIP(host), Port: epPort}
	ctx, cancel := context.WithCancel(context.Background())
	proxyCancel = cancel
	go api.MaintainTunnel(ctx, tlsCfg, 30*time.Second, 1242, endpoint,
		api.NewNetstackAdapter(tunDev), 1280, time.Second)
	endpoint := &net.UDPAddr{IP: net.ParseIP(host), Port: epPort}
	var listener net.Listener
	if mode == "socks5" {
		listener, err = startSocks5(addr, tunNet, dnsAddrs, username, password)
	} else {
		listener, err = startHTTP(addr, tunNet, username, password)
	}
	if err != nil {
		cancel()
		return fmt.Sprintf("listen: %v", err)
	}
	proxyListener = listener
	proxyRunning = true
	return ""
}

func StopProxy() string {
	proxyMu.Lock()
	defer proxyMu.Unlock()
	if !proxyRunning {
		return "proxy not running"
	}
	proxyRunning = false
	if proxyCancel != nil {
		proxyCancel()
	}
	if proxyListener != nil {
		proxyListener.Close()
	}
	return ""
}

func IsProxyRunning() bool {
	proxyMu.Lock()
	defer proxyMu.Unlock()
	return proxyRunning
}

func GetProxyPort() int {
	proxyMu.Lock()
	defer proxyMu.Unlock()
	if proxyListener == nil {
		return 0
	}
	_, ps, _ := net.SplitHostPort(proxyListener.Addr().String())
	var p int
	fmt.Sscanf(ps, "%d", &p)
	return p
}

func startSocks5(addr string, tunNet *netstack.Net, dnsAddrs []netip.Addr, username, password string) (net.Listener, error) {
	r := &tunDNS{tunNet: tunNet, dnsAddrs: dnsAddrs}
	opts := []socks5.Option{
		socks5.WithResolver(r),
		socks5.WithDial(func(ctx context.Context, network, addr string) (net.Conn, error) {
			return tunNet.DialContext(ctx, network, addr)
		}),
	}
	if username != "" && password != "" {
		opts = append(opts, socks5.WithAuthMethods([]socks5.Authenticator{
			socks5.UserPassAuthenticator{Credentials: socks5.StaticCredentials{username: password}},
		}))
	}
	srv := socks5.NewServer(opts...)
	l, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, err
	}
	go func() {
		for {
			c, e := l.Accept()
			if e != nil {
				proxyMu.Lock()
				if !proxyRunning {
					proxyMu.Unlock()
					return
				}
				proxyMu.Unlock()
				continue
			}
			go srv.ServeConn(c)
		}
	}()
	return l, nil
}

func startHTTP(addr string, tunNet *netstack.Net, username, password string) (net.Listener, error) {
	l, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, err
	}
	go func() {
		for {
			c, e := l.Accept()
			if e != nil {
				proxyMu.Lock()
				if !proxyRunning {
					proxyMu.Unlock()
					return
				}
				proxyMu.Unlock()
				continue
			}
			go handleConnect(c, tunNet, username, password)
		}
	}()
	return l, nil
}

func handleConnect(client net.Conn, tunNet *netstack.Net, username, password string) {
	defer client.Close()
	var buf [8192]byte
	n, err := client.Read(buf[:])
	if err != nil || n < 8 {
		return
	}
	req := string(buf[:n])
	if !strings.HasPrefix(req, "CONNECT ") {
		client.Write([]byte("HTTP/1.1 400 Bad Request\r\n\r\n"))
		return
	}
	if username != "" {
		if !checkAuth(req, username, password) {
			client.Write([]byte("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic\r\n\r\n"))
			return
		}
	}
	lines := strings.SplitN(req, "\r\n", 2)
	parts := strings.SplitN(lines[0], " ", 3)
	if len(parts) < 2 {
		return
	}
	target := parts[1]
	if !strings.Contains(target, ":") {
		target += ":443"
	}
	remote, err := tunNet.DialContext(context.Background(), "tcp", target)
	if err != nil {
		client.Write([]byte("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
		return
	}
	defer remote.Close()
	client.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); io.Copy(remote, client) }()
	go func() { defer wg.Done(); io.Copy(client, remote) }()
	wg.Wait()
}

func checkAuth(req, username, password string) bool {
	for _, line := range strings.Split(req, "\r\n") {
		if strings.HasPrefix(line, "Proxy-Authorization: Basic ") {
			enc := strings.TrimPrefix(line, "Proxy-Authorization: Basic ")
			dec, _ := base64.StdEncoding.DecodeString(enc)
			parts := strings.SplitN(string(dec), ":", 2)
			if len(parts) == 2 && parts[0] == username && parts[1] == password {
				return true
			}
		}
	}
	return false
}

type tunDNS struct {
	tunNet   *netstack.Net
	dnsAddrs []netip.Addr
}

func (r *tunDNS) Resolve(ctx context.Context, name string) (context.Context, net.IP, error) {
	for _, addr := range r.dnsAddrs {
		dialer := func(ctx context.Context, network, address string) (net.Conn, error) {
			return r.tunNet.DialContext(ctx, "udp", net.JoinHostPort(addr.String(), "53"))
		}
		resolver := &net.Resolver{PreferGo: true, Dial: dialer}
		ips, err := resolver.LookupIP(ctx, "ip", name)
		if err == nil && len(ips) > 0 {
			return ctx, ips[0], nil
		}
	}
	return ctx, nil, fmt.Errorf("resolve failed")
}

func GetRegisterInfo(configPath string) string {
	if err := config.LoadConfig(configPath); err != nil {
		return "not registered"
	}
	epV4 := config.AppConfig.EndpointV4
	if epV4 == "" {
		epV4 = "(use presets)"
	}
	epV6 := config.AppConfig.EndpointV6
	if epV6 == "" {
		epV6 = "(use presets)"
	}
	return fmt.Sprintf(
		"=== Registered ===\nDeviceID: %s\nAssigned IPv4: %s\nAssigned IPv6: %s\nEndpointV4: %s\nEndpointV6: %s\nLicense: %s\n==================",
		config.AppConfig.ID,
		config.AppConfig.IPv4,
		config.AppConfig.IPv6,
		epV4,
		epV6,
		config.AppConfig.License,
	)
}

func IsRegistered(p string) bool           { return config.LoadConfig(p) == nil }
func GetAssignedIPv4(p string) string       { if config.LoadConfig(p) != nil { return "" }; return config.AppConfig.IPv4 }
func GetAssignedIPv6(p string) string       { if config.LoadConfig(p) != nil { return "" }; return config.AppConfig.IPv6 }
func GetLicense(p string) string            { if config.LoadConfig(p) != nil { return "" }; return config.AppConfig.License }
func GetDeviceID(p string) string           { if config.LoadConfig(p) != nil { return "" }; return config.AppConfig.ID }
func GetEndpointPubKeyPEM(p string) string  { if config.LoadConfig(p) != nil { return "" }; return config.AppConfig.EndpointPubKey }
func GetPrivateKeyB64(p string) string      { if config.LoadConfig(p) != nil { return "" }; return config.AppConfig.PrivateKey }
func GetTunnelStatsRx() uint64              { return atomic.LoadUint64(&stats.RxBytes) }
func GetTunnelStatsTx() uint64              { return atomic.LoadUint64(&stats.TxBytes) }

type AndroidTunDevice struct {
	fd       int
	file     *os.File
	mtu      int
	inputCh  chan []byte
	outputFn PacketFlow
}

func newAndroidTunDevice(fd, mtu int, pf PacketFlow) (*AndroidTunDevice, error) {
	f := os.NewFile(uintptr(fd), "tun")
	if f == nil {
		return nil, fmt.Errorf("cannot create file from fd %d", fd)
	}
	return &AndroidTunDevice{fd: fd, file: f, mtu: mtu, inputCh: make(chan []byte, 256), outputFn: pf}, nil
}
func (d *AndroidTunDevice) ReadPacket(buf []byte) (int, error) {
	n, err := d.file.Read(buf)
	if n > 0 {
		atomic.AddUint64(&stats.RxBytes, uint64(n))
	}
	return n, err
}
func (d *AndroidTunDevice) WritePacket(pkt []byte) error {
	if d.outputFn != nil {
		d.outputFn.WritePacket(pkt)
		return nil
	}
	_, err := d.file.Write(pkt)
	return err
}
func (d *AndroidTunDevice) Close() error {
	if d.file != nil {
		return d.file.Close()
	}
	return nil
}

func StartTunnel(configPath string, tunFd int, mtu int, packetFlow PacketFlow, callback VpnStateCallback) string {
	state.mu.Lock()
	defer state.mu.Unlock()
	if state.running {
		return "already running"
	}
	if err := config.LoadConfig(configPath); err != nil {
		return fmt.Sprintf("config: %v", err)
	}
	privKey, err := config.AppConfig.GetEcPrivateKey()
	if err != nil {
		return fmt.Sprintf("privkey: %v", err)
	}
	peerPubKey, err := config.AppConfig.GetEcEndpointPublicKey()
	if err != nil {
		return fmt.Sprintf("pubkey: %v", err)
	}
	cert, err := internal.GenerateCert(privKey, &privKey.PublicKey)
	if err != nil {
		return fmt.Sprintf("cert: %v", err)
	}
	sni := customSNI
	if sni == "" {
		sni = internal.ConnectSNI
	}
	tlsConfig, err := api.PrepareTlsConfig(privKey, peerPubKey, cert, sni)
	if err != nil {
		return fmt.Sprintf("tls: %v", err)
	}
	tunDevice, err := newAndroidTunDevice(tunFd, mtu, packetFlow)
	if err != nil {
		return fmt.Sprintf("tun: %v", err)
	}

	epStr := ""
	if useIPv6 && customEndpointV6 != "" {
		epStr = customEndpointV6
	} else if customEndpointV4 != "" {
		epStr = customEndpointV4
	} else if useIPv6 && config.AppConfig.EndpointV6 != "" {
		epStr = config.AppConfig.EndpointV6
	} else if config.AppConfig.EndpointV4 != "" {
		epStr = config.AppConfig.EndpointV4
	} else {
		epStr = DefaultEndpointV4Options[0]
	}
	host, port, err := parseEndpoint(epStr)
	if err != nil {
		return fmt.Sprintf("endpoint parse: %v", err)
	}
	endpoint := &net.UDPAddr{IP: net.ParseIP(host), Port: port}

	ctx, cancel := context.WithCancel(context.Background())
	state.cancel = cancel
	state.running = true
	state.callback = callback
	go func() {
		time.Sleep(3 * time.Second)
		state.mu.Lock()
		running := state.running
		state.mu.Unlock()
		if running && callback != nil {
			callback.OnConnected()
		}
	}()
	go func() {
		api.MaintainTunnel(ctx, tlsConfig, 30*time.Second, 1242, endpoint, tunDevice, mtu, time.Second)
		tunDevice.Close()
		state.mu.Lock()
		state.running = false
		state.mu.Unlock()
		if callback != nil {
			callback.OnDisconnected("tunnel closed")
		}
	}()
	return ""
}

func InputPacket(data []byte) {
	state.mu.Lock()
	ch := state.inputChan
	state.mu.Unlock()
	if ch != nil {
		select {
		case ch <- data:
		default:
		}
	}
}
func StopTunnel() {
	state.mu.Lock()
	defer state.mu.Unlock()
	if !state.running {
		return
	}
	if state.cancel != nil {
		state.cancel()
	}
	state.running = false
}
func IsRunning() bool {
	state.mu.Lock()
	defer state.mu.Unlock()
	return state.running
}
func GetVersion() string { return "1.3.1-usque" }

func parseEndpoint(endpoint string) (string, int, error) {
	if len(endpoint) > 0 && endpoint[0] == '[' {
		cb := -1
		for i, c := range endpoint {
			if c == ']' {
				cb = i
				break
			}
		}
		if cb == -1 {
			return "", 0, fmt.Errorf("missing ]")
		}
		host := endpoint[1:cb]
		if cb+1 < len(endpoint) && endpoint[cb+1] == ':' {
			port, err := strconv.Atoi(endpoint[cb+2:])
			if err != nil {
				return "", 0, fmt.Errorf("port: %v", err)
			}
			return host, port, nil
		}
		return host, 443, nil
	}
	lc := strings.LastIndex(endpoint, ":")
	if lc != -1 {
		host := endpoint[:lc]
		port, err := strconv.Atoi(endpoint[lc+1:])
		if err != nil {
			return "", 0, fmt.Errorf("port: %v", err)
		}
		return host, port, nil
	}
	return endpoint, 443, nil
}

func SetSNI(s string)                { customSNI = s }
func GetSNI() string                 { return customSNI }
func SetEndpointV4(s string)         { customEndpointV4 = s }
func GetEndpointV4() string          { return customEndpointV4 }
func SetEndpointV6(s string)         { customEndpointV6 = s }
func GetEndpointV6() string          { return customEndpointV6 }
func SetPrivateKey(s string)         { customPrivateKey = s }
func GetPrivateKey() string          { return customPrivateKey }
func SetEndpointPublicKey(s string)  { customPubKey = s }
func GetEndpointPublicKey() string   { return customPubKey }
func SetJWT(s string)                { customJWT = s }
func GetJWT() string                 { return customJWT }
func SetLicense(s string)            { customLicense = s }
func GetLicenseStr() string          { return customLicense }
func SetToken(s string)              { customToken = s }
func GetToken() string               { return customToken }
func SetAccountID(s string)          { customAccountID = s }
func GetAccountID() string           { return customAccountID }
func SetUseIPv6(b bool)              { useIPv6 = b }
func GetUseIPv6() bool               { return useIPv6 }
func SetEndpoint(s string)           { SetEndpointV4(s) }
func GetEndpoint() string            { return GetEndpointV4() }
func GetDefaultEndpoint(configPath string) string {
	if err := config.LoadConfig(configPath); err == nil {
		return config.AppConfig.EndpointV4
	}
	return ""
}
func ResetConnectionOptions() {
	customSNI = "cdnjs.cloudflare.com"
	customEndpointV4 = ""
	customEndpointV6 = ""
	customPrivateKey = ""
	customPubKey = ""
	customJWT = ""
	customLicense = ""
	customToken = ""
	customAccountID = ""
	useIPv6 = false
	customModel = "PC"
	customLocale = "en_US"
	useZeroTier = false
	customUserAgent = ""
	customClientVersion = ""
	internal.Headers["User-Agent"] = "WARP for Android"
	internal.Headers["CF-Client-Version"] = "a-6.35-4471"
}

func StartTunnelWithFd(configPath string, tunFd int, callback VpnStateCallback) string {
	return StartTunnel(configPath, tunFd, 1280, nil, callback)
}

type fdReadWriter struct {
	file *os.File
}

func (f *fdReadWriter) Read(p []byte) (n int, err error)  { return f.file.Read(p) }
func (f *fdReadWriter) Write(p []byte) (n int, err error)  { return f.file.Write(p) }
func CreateTunReadWriter(fd int) io.ReadWriter             { file := os.NewFile(uintptr(fd), "tun"); return &fdReadWriter{file: file} }
