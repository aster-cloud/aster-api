package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/aster-cloud/aster-api/launcher/internal/orchestrator"
)

type stubOrch struct{}

func (stubOrch) Run(context.Context, orchestrator.RunnerRequest) (orchestrator.RunnerEnvelope, error) {
	return orchestrator.RunnerEnvelope{Outcome: "SUCCESS"}, nil
}

// buildMux 应把 /healthz 与 /api/v1/runner/launch 都装上。
func TestBuildMux_Routes(t *testing.T) {
	mux := buildMux(stubOrch{})

	// /healthz 200。
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest("GET", "/healthz", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("/healthz code=%d", rec.Code)
	}

	// /api/v1/runner/launch 存在（未签名 → 走 handler 的验签路径，非 404）。
	rec2 := httptest.NewRecorder()
	mux.ServeHTTP(rec2, httptest.NewRequest("POST", "/api/v1/runner/launch", nil))
	if rec2.Code == http.StatusNotFound {
		t.Fatal("/api/v1/runner/launch 未装配（404）")
	}
}
