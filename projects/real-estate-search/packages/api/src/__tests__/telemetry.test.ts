import http from 'node:http';
import { SpanKind, SpanStatusCode, trace } from '@opentelemetry/api';
import { InMemorySpanExporter, SimpleSpanProcessor } from '@opentelemetry/sdk-trace-base';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { createTestDatabase, type AppDatabase } from '../db/client.js';
import { seedDatabase } from '../db/seed.js';
import { createGraphqlApp } from '../graphql/server.js';
import {
  extractTraceContext,
  initApiTelemetry,
  JsonConsoleLogRecordExporter,
  logApi,
  normalizeHeaders,
  SeverityNumber,
  shutdownApiTelemetry,
  withApiSpan,
} from '../telemetry.js';

describe('API OpenTelemetry tracing and logging', () => {
  let db: AppDatabase;

  beforeAll(async () => {
    db = await createTestDatabase();
    await seedDatabase(db);
  });

  afterEach(async () => {
    vi.restoreAllMocks();
    delete process.env.OTEL_EXPORTER_OTLP_TRACES_ENDPOINT;
    delete process.env.OTEL_EXPORTER_OTLP_ENDPOINT;
    delete process.env.OTEL_EXPORTER_OTLP_LOGS_ENDPOINT;
    delete process.env.OTEL_SERVICE_NAME;
    await shutdownApiTelemetry();
  });

  it('initializes idempotently and shuts down idempotently', async () => {
    await shutdownApiTelemetry(); // no-op when uninitialized
    initApiTelemetry();
    initApiTelemetry(); // idempotent second call
    await shutdownApiTelemetry();
    await shutdownApiTelemetry(); // idempotent second shutdown
  });

  it('configures OTLP trace and log exporters from environment variables', async () => {
    const receivedPaths: string[] = [];
    const mockCollector = http.createServer((req, res) => {
      receivedPaths.push(req.url ?? '');
      req.resume();
      req.on('end', () => {
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end('{}');
      });
    });
    await new Promise<void>((resolve) => mockCollector.listen(0, '127.0.0.1', resolve));
    const addr = mockCollector.address() as { port: number };
    const baseUrl = `http://127.0.0.1:${addr.port}`;

    try {
      process.env.OTEL_SERVICE_NAME = 'custom-api-service';
      process.env.OTEL_EXPORTER_OTLP_ENDPOINT = `${baseUrl}/`;
      process.env.OTEL_EXPORTER_OTLP_LOGS_ENDPOINT = `${baseUrl}/v1/logs`;

      const memoryExporter = new InMemorySpanExporter();
      initApiTelemetry({
        spanProcessors: [new SimpleSpanProcessor(memoryExporter)],
      });

      const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
      await withApiSpan('env.test.span', { test: true }, () => {
        logApi(SeverityNumber.INFO, 'INFO', 'env.test.log', { ok: true });
      });

      expect(consoleSpy).toHaveBeenCalled();
      const firstLog = JSON.parse(String(consoleSpy.mock.calls[0]?.[0]));
      expect(firstLog.service).toBe('custom-api-service');
      expect(firstLog.traceId).toMatch(/^[0-9a-f]{32}$/);
      expect(firstLog.spanId).toMatch(/^[0-9a-f]{16}$/);

      await shutdownApiTelemetry();
      expect(receivedPaths).toContain('/v1/traces');
      expect(receivedPaths).toContain('/v1/logs');
    } finally {
      await new Promise<void>((resolve) => mockCollector.close(() => resolve()));
    }
  });

  it('supports explicit OTEL_EXPORTER_OTLP_TRACES_ENDPOINT', async () => {
    process.env.OTEL_EXPORTER_OTLP_TRACES_ENDPOINT = 'http://localhost:4318/v1/traces';
    initApiTelemetry();
    await shutdownApiTelemetry();
  });

  it('normalizes Headers and plain header records case-insensitively and extracts W3C traceparent', () => {
    initApiTelemetry();

    const traceId = '4bf92f3577b34da6a3ce929d0e0e4736';
    const parentSpanId = '00f067aa0ba902b7';
    const traceparent = `00-${traceId}-${parentSpanId}-01`;

    expect(normalizeHeaders(undefined)).toEqual({});

    const fetchHeaders = new Headers({
      TraceParent: traceparent,
      TraceState: 'congo=t61rcWkgMzE',
    });
    expect(normalizeHeaders(fetchHeaders)).toEqual({
      traceparent,
      tracestate: 'congo=t61rcWkgMzE',
    });

    const nodeHeaders = {
      TRACEPARENT: [traceparent],
      'X-Ignored': undefined,
      'X-Single': 'abc',
    };
    expect(normalizeHeaders(nodeHeaders)).toEqual({
      traceparent,
      'x-single': 'abc',
    });

    const extracted = extractTraceContext(fetchHeaders);
    const extractedSpanCtx = trace.getSpanContext(extracted);
    expect(extractedSpanCtx?.traceId).toBe(traceId);
    expect(extractedSpanCtx?.spanId).toBe(parentSpanId);
  });

  it('links incoming W3C traceparent to GraphQL server and search DB spans and emits trace-correlated JSON logs', async () => {
    const memoryExporter = new InMemorySpanExporter();
    initApiTelemetry({
      spanProcessors: [new SimpleSpanProcessor(memoryExporter)],
    });

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
    const yoga = createGraphqlApp(db);

    const incomingTraceId = '11111111222222223333333344444444';
    const incomingSpanId = '5555555566666666';
    const traceparent = `00-${incomingTraceId}-${incomingSpanId}-01`;

    const response = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        TraceParent: traceparent,
      },
      body: JSON.stringify({
        query: `query Search($input: SearchListingsInput!) {
          searchListings(input: $input) {
            ok
            listings { id city }
            pagination { totalResults }
          }
        }`,
        variables: { input: { city: 'Austin', page: 1, pageSize: 5 } },
      }),
    });

    expect(response.status).toBe(200);
    const spans = memoryExporter.getFinishedSpans();
    const spanNames = spans.map((s) => s.name);

    expect(spanNames).toContain('POST /graphql');
    expect(spanNames).toContain('graphql.searchListings');
    expect(spanNames).toContain('search.execute');
    expect(spanNames).toContain('search.validateInput');
    expect(spanNames).toContain('search.db.count');
    expect(spanNames).toContain('search.db.query');

    const serverSpan = spans.find((s) => s.name === 'POST /graphql');
    expect(serverSpan?.kind).toBe(SpanKind.SERVER);
    expect(serverSpan?.spanContext().traceId).toBe(incomingTraceId);
    expect(serverSpan?.parentSpanContext?.spanId).toBe(incomingSpanId);
    expect(serverSpan?.attributes['http.request.method']).toBe('POST');
    expect(serverSpan?.attributes['url.path']).toBe('/graphql');
    expect(serverSpan?.attributes['http.response.status_code']).toBe(200);

    for (const s of spans) {
      expect(s.spanContext().traceId).toBe(incomingTraceId);
    }

    const dbCountSpan = spans.find((s) => s.name === 'search.db.count');
    expect(dbCountSpan?.kind).toBe(SpanKind.CLIENT);
    expect(dbCountSpan?.attributes['db.system']).toBe('postgresql');
    expect(dbCountSpan?.attributes['db.collection.name']).toBe('listings');

    const dbQuerySpan = spans.find((s) => s.name === 'search.db.query');
    expect(dbQuerySpan?.kind).toBe(SpanKind.CLIENT);
    expect(dbQuerySpan?.attributes['db.system']).toBe('postgresql');
    expect(dbQuerySpan?.attributes['db.collection.name']).toBe('listings');

    const logEntries = consoleSpy.mock.calls.map((call) => JSON.parse(String(call[0])));
    expect(logEntries.length).toBeGreaterThanOrEqual(3);
    for (const entry of logEntries) {
      expect(entry.traceId).toBe(incomingTraceId);
      expect(entry.spanId).toMatch(/^[0-9a-f]{16}$/);
      expect(entry.service).toBe('real-estate-api');
    }
    expect(logEntries.map((e) => e.body)).toContain('search.started');
    expect(logEntries.map((e) => e.body)).toContain('search.completed');
    expect(logEntries.map((e) => e.body)).toContain('http.request.completed');
  });

  it('leaves SERVER span UNSET on 400 validation errors with WARN log and sets ERROR on 500 internal errors', async () => {
    const memoryExporter = new InMemorySpanExporter();
    initApiTelemetry({
      spanProcessors: [new SimpleSpanProcessor(memoryExporter)],
    });

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
    const consoleErrSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

    const yoga = createGraphqlApp(db);
    const badRes = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: `query Search($input: SearchListingsInput!) {
          searchListings(input: $input) { ok error { code message } }
        }`,
        variables: { input: { minPrice: 'invalid' } },
      }),
    });
    expect(badRes.status).toBe(400);

    const serverSpan400 = memoryExporter.getFinishedSpans().find((s) => s.name === 'POST /graphql');
    expect(serverSpan400?.attributes['http.response.status_code']).toBe(400);
    expect(serverSpan400?.status.code).toBe(SpanStatusCode.UNSET);

    const warnLogs = consoleSpy.mock.calls.map((call) => JSON.parse(String(call[0])));
    expect(warnLogs.some((l) => l.body === 'http.request.rejected' && l.severityText === 'WARN')).toBe(true);

    memoryExporter.reset();
    const brokenDb = {
      select: () => {
        throw new Error('DB down');
      },
    } as unknown as AppDatabase;
    const brokenYoga = createGraphqlApp(brokenDb);
    const errRes = await brokenYoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: `query Search { searchListings { ok error { code message } } }`,
      }),
    });
    expect(errRes.status).toBe(500);
    expect(consoleErrSpy).toHaveBeenCalled();

    const serverSpan500 = memoryExporter.getFinishedSpans().find((s) => s.name === 'POST /graphql');
    expect(serverSpan500?.attributes['http.response.status_code']).toBe(500);
    expect(serverSpan500?.status.code).toBe(SpanStatusCode.ERROR);
  });

  it('records exception and sets ERROR status when instrumentation.request wrapped call throws', async () => {
    const memoryExporter = new InMemorySpanExporter();
    initApiTelemetry({
      spanProcessors: [new SimpleSpanProcessor(memoryExporter)],
    });

    vi.spyOn(trace, 'getActiveSpan').mockImplementationOnce(() => {
      throw new Error('Unhandled instrumentation failure');
    });

    const yoga = createGraphqlApp(db);
    await expect(
      yoga.fetch('http://localhost:4000/graphql', {
        method: 'OPTIONS',
        headers: {
          Origin: 'http://localhost:5173',
          'Access-Control-Request-Method': 'POST',
        },
      })
    ).rejects.toThrow('Unhandled instrumentation failure');

    const serverSpan = memoryExporter.getFinishedSpans().find((s) => s.name === 'OPTIONS /graphql');
    expect(serverSpan?.status.code).toBe(SpanStatusCode.ERROR);
    expect(serverSpan?.status.message).toBe('Unhandled instrumentation failure');
    expect(serverSpan?.events.some((e) => e.name === 'exception')).toBe(true);
  });

  it('allows traceparent and tracestate headers in CORS preflight responses', async () => {
    initApiTelemetry();
    vi.spyOn(console, 'log').mockImplementation(() => {});
    const yoga = createGraphqlApp(db);

    const response = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'OPTIONS',
      headers: {
        Origin: 'http://localhost:5173',
        'Access-Control-Request-Method': 'POST',
        'Access-Control-Request-Headers': 'content-type, traceparent, tracestate',
      },
    });
    expect([200, 204]).toContain(response.status);
    const allowedHeaders = response.headers.get('access-control-allow-headers')?.toLowerCase() ?? '';
    expect(allowedHeaders).toContain('traceparent');
    expect(allowedHeaders).toContain('tracestate');
  });

  it('handles logApi outside active span, withApiSpan exceptions, and JsonConsoleLogRecordExporter methods', async () => {
    const memoryExporter = new InMemorySpanExporter();
    initApiTelemetry({
      spanProcessors: [new SimpleSpanProcessor(memoryExporter)],
    });

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
    logApi(SeverityNumber.INFO, 'INFO', 'outside.span', { detail: 'none' });

    const outsideLog = JSON.parse(String(consoleSpy.mock.calls[0]?.[0]));
    expect(outsideLog.traceId).toBeNull();
    expect(outsideLog.spanId).toBeNull();

    await expect(
      withApiSpan('failing.span', {}, () => {
        throw 'non-error failure';
      })
    ).rejects.toBe('non-error failure');

    const failedSpan = memoryExporter.getFinishedSpans().find((s) => s.name === 'failing.span');
    expect(failedSpan?.status.code).toBe(SpanStatusCode.ERROR);

    const exporter = new JsonConsoleLogRecordExporter('test-svc');
    const resultCb = vi.fn();
    exporter.export(
      [
        {
          hrTime: [1700000000, 0],
          attributes: {},
          droppedAttributesCount: 0,
          body: 'minimal-record',
          instrumentationScope: { name: 'test' },
          resource: {} as never,
          hrTimeObserved: [1700000000, 0],
        },
      ],
      resultCb
    );
    expect(resultCb).toHaveBeenCalledWith({ code: 0 });
    await exporter.forceFlush();
    await exporter.shutdown();
  });
});
