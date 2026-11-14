import React from 'react';
import { context, propagation, trace } from '@opentelemetry/api';
import { InMemorySpanExporter, SimpleSpanProcessor } from '@opentelemetry/sdk-trace-base';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { SearchResponse } from '@real-estate/shared';
import { App } from '../App.js';
import {
  getTracer,
  initUiTelemetry,
  injectTraceHeaders,
  JsonBrowserLogRecordExporter,
  logUi,
  shutdownUiTelemetry,
} from '../telemetry.js';

describe('UI OpenTelemetry tracing and logging', () => {
  afterEach(async () => {
    vi.restoreAllMocks();
    await shutdownUiTelemetry();
  });

  it('initializes and shuts down idempotently, including network OTLP option', async () => {
    await shutdownUiTelemetry(); // no-op when uninitialized
    initUiTelemetry();
    initUiTelemetry(); // idempotent second call
    await shutdownUiTelemetry();
    await shutdownUiTelemetry(); // idempotent second shutdown

    initUiTelemetry({
      serviceName: 'custom-ui',
      tracesEndpoint: 'http://localhost:4318/v1/traces',
      enableNetworkExport: true,
    });
    await shutdownUiTelemetry();
  });

  it('injects W3C traceparent and tracestate headers from active context', () => {
    const memoryExporter = new InMemorySpanExporter();
    initUiTelemetry({
      spanProcessors: [new SimpleSpanProcessor(memoryExporter)],
    });

    const span = getTracer().startSpan('test.inject');
    const spanCtx = trace.setSpan(context.active(), span);

    const headers = context.with(spanCtx, () => injectTraceHeaders());
    const expectedTraceId = span.spanContext().traceId;
    const expectedSpanId = span.spanContext().spanId;
    expect(headers.traceparent).toBe(`00-${expectedTraceId}-${expectedSpanId}-01`);

    const extracted = propagation.extract(context.active(), {
      traceparent: headers.traceparent,
      tracestate: 'vendor=value',
    });
    const reInjected = injectTraceHeaders({ 'X-Custom': '1' }, extracted);
    expect(reInjected['X-Custom']).toBe('1');
    expect(reInjected.traceparent).toBe(headers.traceparent);
    expect(reInjected.tracestate).toBe('vendor=value');

    span.end();
  });

  it('emits structured JSON console logs correlated with active span and adds span events', () => {
    const memoryExporter = new InMemorySpanExporter();
    initUiTelemetry({
      spanProcessors: [new SimpleSpanProcessor(memoryExporter)],
    });

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => {});

    // Outside active span
    logUi('DEBUG', 'ui.outside.span', { step: 1 });
    const outsideEntry = JSON.parse(String(consoleSpy.mock.calls[0]?.[0]));
    expect(outsideEntry.traceId).toBeNull();
    expect(outsideEntry.spanId).toBeNull();
    expect(outsideEntry.severityText).toBe('DEBUG');

    // Inside active span
    const span = getTracer().startSpan('ui.logged.span');
    const spanCtx = trace.setSpan(context.active(), span);
    logUi('INFO', 'ui.inside.info', { count: 2 }, spanCtx);
    logUi('WARN', 'ui.inside.warn', { code: 'VALIDATION_ERROR' }, spanCtx);
    logUi('ERROR', 'ui.inside.error', { code: 'INTERNAL_ERROR' }, spanCtx);
    span.end();

    const finished = memoryExporter.getFinishedSpans().find((s) => s.name === 'ui.logged.span');
    expect(finished?.events.map((e) => e.name)).toEqual([
      'ui.inside.info',
      'ui.inside.warn',
      'ui.inside.error',
    ]);

    const insideEntry = JSON.parse(String(consoleSpy.mock.calls[1]?.[0]));
    expect(insideEntry.service).toBe('real-estate-ui');
    expect(insideEntry.traceId).toBe(span.spanContext().traceId);
    expect(insideEntry.spanId).toBe(span.spanContext().spanId);
    expect(insideEntry.body).toBe('ui.inside.info');
  });

  it('handles JsonBrowserLogRecordExporter export, forceFlush, and shutdown', async () => {
    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
    const exporter = new JsonBrowserLogRecordExporter('test-ui');
    const cb = vi.fn();

    exporter.export(
      [
        {
          hrTime: [1700000000, 0],
          attributes: {},
          droppedAttributesCount: 0,
          body: 'minimal-ui-log',
          instrumentationScope: { name: 'test' },
          resource: {} as never,
          hrTimeObserved: [1700000000, 0],
        },
      ],
      cb
    );

    expect(cb).toHaveBeenCalledWith({ code: 0 });
    const parsed = JSON.parse(String(consoleSpy.mock.calls[0]?.[0]));
    expect(parsed.service).toBe('test-ui');
    expect(parsed.severityText).toBe('UNSPECIFIED');
    await exporter.forceFlush();
    await exporter.shutdown();
  });

  it('traces and logs user search lifecycle in App with consistent traceId', async () => {
    const memoryExporter = new InMemorySpanExporter();
    initUiTelemetry({
      spanProcessors: [new SimpleSpanProcessor(memoryExporter)],
    });

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
    const user = userEvent.setup();

    const sampleResponse: SearchResponse = {
      ok: true,
      listings: [
        {
          id: 'LST-1',
          address: '100 Main St, Austin, TX',
          city: 'Austin',
          price: 450000,
          bedrooms: 3,
          description: 'Nice home',
          listedDate: '2026-10-20T00:00:00.000Z',
          relevanceScore: 0.9,
        },
      ],
      pagination: { page: 1, pageSize: 10, totalResults: 1, totalPages: 1 },
      error: null,
    };

    const searchFn = vi.fn().mockResolvedValue(sampleResponse);
    render(React.createElement(App, { searchFn }));
    await user.click(screen.getByRole('button', { name: /search/i }));
    await screen.findByText('100 Main St, Austin, TX');

    const userSpan = memoryExporter.getFinishedSpans().find((s) => s.name === 'ui.user.search');
    expect(userSpan).toBeDefined();
    expect(userSpan?.attributes['ui.action']).toBe('search');

    const logs = consoleSpy.mock.calls.map((c) => JSON.parse(String(c[0])));
    const submitted = logs.find((l) => l.body === 'ui.search.submitted');
    const rendered = logs.find((l) => l.body === 'ui.search.results_rendered');
    expect(submitted?.traceId).toBe(userSpan?.spanContext().traceId);
    expect(rendered?.traceId).toBe(userSpan?.spanContext().traceId);
  });
});
