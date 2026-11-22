package main

import (
	"bytes"
	"context"
	"errors"
	"net"
	"testing"
	"time"

	"tg-ws-proxy/mtproxyfrontend"
)

func TestMtProtoDirectWSConnectorSendsRelayInit(t *testing.T) {
	socket := &fakeMtProtoFrameSocket{}
	var gotTarget string
	var gotDomain string
	connector := &mtProtoDirectWSConnector{
		resolveTarget: func(dc int) (string, int, bool) {
			if dc != 2 {
				t.Errorf("dc=%d", dc)
			}
			return "149.154.167.51", 443, true
		},
		dial: func(targetIP, domain, path string, timeout float64) (mtProtoFrameSocket, error) {
			gotTarget = targetIP
			gotDomain = domain
			if path != "/apiws" {
				t.Errorf("path=%s", path)
			}
			if timeout != 10 {
				t.Errorf("timeout=%v", timeout)
			}
			return socket, nil
		},
	}

	relayInit := buildTestInitWithSignedDC(t, 2)
	conn, result := connector.Connect(context.Background(), mtproxyfrontend.OutboundRequest{
		DCID:      2,
		Transport: mtproxyfrontend.TransportAbridged,
		RelayInit: relayInit,
	})
	if result.Err != nil {
		t.Fatalf("connect: %v", result.Err)
	}
	defer conn.Close()

	if gotTarget != "149.154.167.51" {
		t.Fatalf("target=%s", gotTarget)
	}
	if gotDomain != "kws2.web.telegram.org" {
		t.Fatalf("domain=%s", gotDomain)
	}
	if result.ActualBackend != mtProtoDirectWSBackend || result.Reason != "connected" {
		t.Fatalf("route truth=%+v", result)
	}
	if len(socket.sent) != 1 || !bytes.Equal(socket.sent[0], relayInit) {
		t.Fatal("relay init was not sent to direct WebSocket")
	}
}

func TestMtProtoCFProxyConnectorUsesCFDomainPool(t *testing.T) {
	withRuntimeSettings(t, func(settings runtimeSettings) runtimeSettings {
		settings.CF.Enabled = true
		settings.CF.Only = false
		settings.CF.Priority = false
		return settings
	})
	withTestCFPool(t, []string{"pool.example"})

	socket := &fakeMtProtoFrameSocket{}
	var gotDomain string
	connector := &mtProtoCFProxyConnector{
		dial: func(domain, path, _ string) (mtProtoFrameSocket, error) {
			gotDomain = domain
			if path != "/apiws" {
				t.Errorf("path=%s", path)
			}
			return socket, nil
		},
	}

	relayInit := buildTestInitWithSignedDC(t, 2)
	conn, result := connector.Connect(context.Background(), mtproxyfrontend.OutboundRequest{
		DCID:      2,
		Transport: mtproxyfrontend.TransportAbridged,
		RelayInit: relayInit,
	})
	if result.Err != nil {
		t.Fatalf("connect: %v", result.Err)
	}
	defer conn.Close()

	if gotDomain != "kws2.pool.example" {
		t.Fatalf("domain=%s", gotDomain)
	}
	if result.ActualBackend != mtProtoCFProxyBackend || result.Reason != "connected" {
		t.Fatalf("route truth=%+v", result)
	}
	if len(socket.sent) != 1 || !bytes.Equal(socket.sent[0], relayInit) {
		t.Fatal("relay init was not sent to CF proxy")
	}
}

func TestMtProtoCFProxyConnectorFastSkipsCachedAfterDNSFailure(t *testing.T) {
	withRuntimeSettings(t, func(settings runtimeSettings) runtimeSettings {
		settings.CF.Enabled = true
		settings.CF.Only = false
		settings.CF.Priority = false
		return settings
	})

	previous := cfPool
	cfPool = newCFDomainPool()
	cfPool.SetCachedUpstreamDomains([]string{"cached-a.example", "cached-b.example"})
	cfPool.SetBuiltinDomains([]string{"builtin.example"})
	t.Cleanup(func() {
		cfPool = previous
	})

	socket := &fakeMtProtoFrameSocket{}
	var attempts []string
	connector := &mtProtoCFProxyConnector{
		dial: func(domain, path, _ string) (mtProtoFrameSocket, error) {
			attempts = append(attempts, domain)
			if path != "/apiws" {
				t.Errorf("path=%s", path)
			}
			if domain == "kws2.builtin.example" {
				return socket, nil
			}
			return nil, &wsStageError{
				Stage: "tcp_dial",
				Err: &net.DNSError{
					Err:        "no such host",
					Name:       domain,
					IsNotFound: true,
				},
			}
		},
	}

	relayInit := buildTestInitWithSignedDC(t, 2)
	conn, result := connector.Connect(context.Background(), mtproxyfrontend.OutboundRequest{
		DCID:      2,
		Transport: mtproxyfrontend.TransportAbridged,
		RelayInit: relayInit,
	})
	if result.Err != nil {
		t.Fatalf("connect: %v", result.Err)
	}
	defer conn.Close()

	wantAttempts := []string{"kws2.cached-a.example", "kws2.builtin.example"}
	if len(attempts) != len(wantAttempts) {
		t.Fatalf("attempts=%v want %v", attempts, wantAttempts)
	}
	for i := range wantAttempts {
		if attempts[i] != wantAttempts[i] {
			t.Fatalf("attempts=%v want %v", attempts, wantAttempts)
		}
	}
	if !cfPool.IsCoolingDown(2, "cached-a.example") {
		t.Fatal("cached domain should be cooling down after DNS failure")
	}
}

func TestMtProtoDirectWSConnectorUsesTestDCPathAndTarget(t *testing.T) {
	socket := &fakeMtProtoFrameSocket{}
	var gotTarget string
	var gotPath string
	connector := &mtProtoDirectWSConnector{
		resolveTarget: func(int) (string, int, bool) {
			t.Fatal("production target resolver must not be used for test DC")
			return "", 0, false
		},
		dial: func(targetIP, _ string, path string, _ float64) (mtProtoFrameSocket, error) {
			gotTarget = targetIP
			gotPath = path
			return socket, nil
		},
	}

	relayInit := buildTestInitWithSignedDC(t, 2)
	conn, result := connector.Connect(context.Background(), mtproxyfrontend.OutboundRequest{
		DCID:      2,
		IsTestDC:  true,
		Transport: mtproxyfrontend.TransportAbridged,
		RelayInit: relayInit,
	})
	if result.Err != nil {
		t.Fatalf("connect: %v", result.Err)
	}
	defer conn.Close()

	if gotTarget != "149.154.167.40" {
		t.Fatalf("target=%s", gotTarget)
	}
	if gotPath != "/apiws_test" {
		t.Fatalf("path=%s", gotPath)
	}
}

func TestMtProtoDirectWSConnectorSkipsIPOnCooldownWhenFallbackExists(t *testing.T) {
	clearDirectIPCooldowns()
	t.Cleanup(clearDirectIPCooldowns)
	withRuntimeSettings(t, func(settings runtimeSettings) runtimeSettings {
		settings.Mode = modeDirectWithFallback
		settings.PolicyPresent = false
		return settings
	})
	markDirectIPTimeout("149.154.167.51", monoNow())

	connector := &mtProtoDirectWSConnector{
		resolveTarget: func(int) (string, int, bool) {
			return "149.154.167.51", 443, true
		},
		dial: func(string, string, string, float64) (mtProtoFrameSocket, error) {
			t.Fatal("direct WS dial should be skipped while IP cooldown is active")
			return nil, nil
		},
	}

	conn, result := connector.Connect(context.Background(), mtproxyfrontend.OutboundRequest{
		DCID:      2,
		Transport: mtproxyfrontend.TransportAbridged,
		RelayInit: buildTestInitWithSignedDC(t, 2),
	})
	if conn != nil {
		t.Fatal("unexpected connection")
	}
	if result.Reason != "direct_ip_cooldown" {
		t.Fatalf("reason=%s", result.Reason)
	}
	if result.Err == nil {
		t.Fatal("expected cooldown error")
	}
}

func TestMtProtoDirectWSConnectorMarksTimeoutIPCooldown(t *testing.T) {
	clearDirectIPCooldowns()
	t.Cleanup(clearDirectIPCooldowns)
	connector := &mtProtoDirectWSConnector{
		resolveTarget: func(int) (string, int, bool) {
			return "149.154.167.51", 443, true
		},
		dial: func(string, string, string, float64) (mtProtoFrameSocket, error) {
			return nil, &wsStageError{Stage: "tcp_dial", Err: timeoutErr{}}
		},
	}

	conn, result := connector.Connect(context.Background(), mtproxyfrontend.OutboundRequest{
		DCID:      2,
		Transport: mtproxyfrontend.TransportAbridged,
		RelayInit: buildTestInitWithSignedDC(t, 2),
	})
	if conn != nil {
		t.Fatal("unexpected connection")
	}
	if result.Err == nil {
		t.Fatal("expected timeout error")
	}
	if result.Reason != "direct_ws_ip_timeout" {
		t.Fatalf("reason=%s", result.Reason)
	}
	if !directIPCoolingDown("149.154.167.51", monoNow()) {
		t.Fatal("expected direct IP cooldown after timeout")
	}
}

func withTestCFPool(t *testing.T, domains []string) {
	t.Helper()
	previous := cfPool
	cfPool = newCFDomainPool()
	cfPool.SetBuiltinDomains(domains)
	t.Cleanup(func() {
		cfPool = previous
	})
}

type timeoutErr struct{}

func (timeoutErr) Error() string {
	return "timeout"
}

func (timeoutErr) Timeout() bool {
	return true
}

func (timeoutErr) Temporary() bool {
	return true
}

func TestMtProtoCFProxyConnectorFastSkipsCachedAfterTwoNonDNSFailures(t *testing.T) {
	withRuntimeSettings(t, func(settings runtimeSettings) runtimeSettings {
		settings.CF.Enabled = true
		settings.CF.Only = false
		settings.CF.Priority = false
		return settings
	})

	previous := cfPool
	cfPool = newCFDomainPool()
	cfPool.SetCachedUpstreamDomains([]string{"cached-1.example", "cached-2.example", "cached-3.example"})
	cfPool.SetBuiltinDomains([]string{"builtin.example"})
	t.Cleanup(func() {
		cfPool = previous
	})

	socket := &fakeMtProtoFrameSocket{}
	var attempts []string
	connector := &mtProtoCFProxyConnector{
		dial: func(domain, path, _ string) (mtProtoFrameSocket, error) {
			attempts = append(attempts, domain)
			if domain == "kws2.builtin.example" {
				return socket, nil
			}
			return nil, errors.New("connection timeout")
		},
	}

	relayInit := buildTestInitWithSignedDC(t, 2)
	conn, result := connector.Connect(context.Background(), mtproxyfrontend.OutboundRequest{
		DCID:      2,
		Transport: mtproxyfrontend.TransportAbridged,
		RelayInit: relayInit,
	})
	if result.Err != nil {
		t.Fatalf("connect: %v", result.Err)
	}
	defer conn.Close()

	// Should try first two cached upstream domains, then fast-skip cached-3 and reach builtin.
	if len(attempts) != 3 {
		t.Fatalf("attempts=%v want 3 attempts ending at kws2.builtin.example", attempts)
	}
	if attempts[2] != "kws2.builtin.example" {
		t.Fatalf("last attempt=%s want kws2.builtin.example (attempts=%v)", attempts[2], attempts)
	}
}

func TestResetNativeTransientConnectionStateClearsCooldownsAndMissSamples(t *testing.T) {
	previous := cfPool
	cfPool = newCFDomainPool()
	t.Cleanup(func() {
		cfPool = previous
	})

	cfPool.SetBuiltinDomains([]string{"builtin.example"})
	cfPool.MarkFailure(2, "builtin.example", "http_403", 100)
	if !cfPool.IsCoolingDown(2, "builtin.example") {
		t.Fatal("expected CF domain cooldown before reset")
	}

	markDirectIPTimeout("149.154.167.51", monoNow())
	if !directIPCoolingDown("149.154.167.51", monoNow()) {
		t.Fatal("expected direct IP cooldown before reset")
	}

	now := time.Now()
	markDirectTCPFailure(2, "149.154.167.51", 443, directTCPFailureTimeout, now)
	if _, active := activeDirectTCPCooldown(2, "149.154.167.51", 443, now.Add(time.Second)); !active {
		t.Fatal("expected direct TCP cooldown before reset")
	}

	if count := countCFPoolMiss(2, now); count != 1 {
		t.Fatalf("miss count=%d want 1", count)
	}

	resetNativeTransientConnectionState()

	if cfPool.IsCoolingDown(2, "builtin.example") {
		t.Fatal("CF domain cooldown should be cleared after reset")
	}
	if directIPCoolingDown("149.154.167.51", monoNow()) {
		t.Fatal("direct IP cooldown should be cleared after reset")
	}
	if _, active := activeDirectTCPCooldown(2, "149.154.167.51", 443, now.Add(time.Second)); active {
		t.Fatal("direct TCP cooldown should be cleared after reset")
	}
	if count := countCFPoolMiss(2, now.Add(time.Second)); count != 1 {
		t.Fatalf("miss count after reset=%d want 1", count)
	}
}
