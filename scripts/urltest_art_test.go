package urltest

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
)

type artURLTestDialer struct {
	address string
}

func (d artURLTestDialer) DialContext(ctx context.Context, network string, _ M.Socksaddr) (net.Conn, error) {
	return (&net.Dialer{}).DialContext(ctx, network, d.address)
}

func TestARTURLTestAcceptsOnlyHTTP204(t *testing.T) {
	tests := []struct {
		name      string
		status    int
		wantError bool
	}{
		{name: "204 health response", status: http.StatusNoContent, wantError: false},
		{name: "200 captive portal page", status: http.StatusOK, wantError: true},
		{name: "404 error page", status: http.StatusNotFound, wantError: true},
		{name: "503 unavailable", status: http.StatusServiceUnavailable, wantError: true},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method != http.MethodHead {
					t.Errorf("health check method = %s, want HEAD", r.Method)
				}
				w.WriteHeader(test.status)
			}))
			defer server.Close()

			parsed, err := url.Parse(server.URL)
			if err != nil {
				t.Fatal(err)
			}
			port, err := strconv.Atoi(parsed.Port())
			if err != nil {
				t.Fatal(err)
			}
			dialer := artURLTestDialer{address: net.JoinHostPort(parsed.Hostname(), strconv.Itoa(port))}
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()

			_, err = urlTest(ctx, server.URL, dialer)
			if test.wantError && err == nil {
				t.Fatalf("urlTest accepted HTTP %d; expected a health-check error", test.status)
			}
			if !test.wantError && err != nil {
				t.Fatalf("urlTest rejected HTTP %d: %v", test.status, err)
			}
		})
	}
}
