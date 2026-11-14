import {
  context,
  propagation,
  trace,
  SpanStatusCode,
  type Attributes,
  type Context,
  type Span,
  type Tracer,
} from '@opentelemetry/api';
import { logs, SeverityNumber, type LogAttributes } from '@opentelemetry/api-logs';
import { ExportResultCode, W3CTraceContextPropagator, type ExportResult } from '@opentelemetry/core';
import { OTLPLogExporter } from '@opentelemetry/exporter-logs-otlp-http';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { resourceFromAttributes } from '@opentelemetry/resources';
import {
  LoggerProvider,
  SimpleLogRecordProcessor,
  type LogRecordExporter,
  type LogRecordProcessor,
  type ReadableLogRecord,
} from '@opentelemetry/sdk-logs';
import { SimpleSpanProcessor, type SpanProcessor } from '@opentelemetry/sdk-trace-base';
import { NodeTracerProvider } from '@opentelemetry/sdk-trace-node';
import { ATTR_SERVICE_NAME } from '@opentelemetry/semantic-conventions';

export { SeverityNumber };

export type IncomingHeaders =
  | Headers
  | Record<string, string | string[] | undefined>
  | undefined;

export interface InitApiTelemetryOptions {
  serviceName?: string;
  spanProcessors?: SpanProcessor[];
  logProcessors?: LogRecordProcessor[];
}

/**
 * Exports OpenTelemetry log records as single-line structured JSON to stdout.
 */
export class JsonConsoleLogRecordExporter implements LogRecordExporter {
  private readonly serviceName: string;

  constructor(serviceName: string) {
    this.serviceName = serviceName;
  }

  export(logsBatch: ReadableLogRecord[], resultCallback: (result: ExportResult) => void): void {
    for (const record of logsBatch) {
      const timestamp = new Date(
        record.hrTime[0] * 1000 + Math.floor(record.hrTime[1] / 1_000_000)
      ).toISOString();
      const entry = {
        timestamp,
        service: this.serviceName,
        severityNumber: record.severityNumber ?? SeverityNumber.UNSPECIFIED,
        severityText: record.severityText ?? 'UNSPECIFIED',
        body: record.body,
        traceId: record.spanContext?.traceId ?? null,
        spanId: record.spanContext?.spanId ?? null,
        traceFlags: record.spanContext?.traceFlags ?? null,
        attributes: record.attributes,
      };
      console.log(JSON.stringify(entry));
    }
    resultCallback({ code: ExportResultCode.SUCCESS });
  }

  async shutdown(): Promise<void> {}

  async forceFlush(): Promise<void> {}
}

let tracerProvider: NodeTracerProvider | null = null;
let loggerProvider: LoggerProvider | null = null;
let initialized = false;

function resolveTracesEndpoint(): string | undefined {
  const explicitTraces = process.env.OTEL_EXPORTER_OTLP_TRACES_ENDPOINT?.trim();
  if (explicitTraces) {
    return explicitTraces;
  }
  const baseEndpoint = process.env.OTEL_EXPORTER_OTLP_ENDPOINT?.trim();
  if (baseEndpoint) {
    return `${baseEndpoint.replace(/\/+$/, '')}/v1/traces`;
  }
  return undefined;
}

function resolveLogsEndpoint(): string | undefined {
  // Only export OTLP logs when OTEL_EXPORTER_OTLP_LOGS_ENDPOINT is explicitly set,
  // avoiding 404s from Jaeger OTLP collectors that only ingest traces.
  const explicitLogs = process.env.OTEL_EXPORTER_OTLP_LOGS_ENDPOINT?.trim();
  return explicitLogs || undefined;
}

/**
 * Initializes OpenTelemetry tracing and structured logging for the API service.
 * Idempotent across repeated invocations.
 */
export function initApiTelemetry(options: InitApiTelemetryOptions = {}): void {
  if (initialized) {
    return;
  }

  const serviceName =
    options.serviceName ?? (process.env.OTEL_SERVICE_NAME?.trim() || 'real-estate-api');
  const resource = resourceFromAttributes({
    [ATTR_SERVICE_NAME]: serviceName,
  });

  const spanProcessors: SpanProcessor[] = [...(options.spanProcessors ?? [])];
  const tracesEndpoint = resolveTracesEndpoint();
  if (tracesEndpoint) {
    spanProcessors.push(new SimpleSpanProcessor(new OTLPTraceExporter({ url: tracesEndpoint })));
  }

  const propagator = new W3CTraceContextPropagator();
  tracerProvider = new NodeTracerProvider({
    resource,
    spanProcessors,
  });
  tracerProvider.register({ propagator });
  propagation.setGlobalPropagator(propagator);

  const logProcessors: LogRecordProcessor[] = [
    new SimpleLogRecordProcessor({ exporter: new JsonConsoleLogRecordExporter(serviceName) }),
    ...(options.logProcessors ?? []),
  ];
  const logsEndpoint = resolveLogsEndpoint();
  if (logsEndpoint) {
    logProcessors.push(
      new SimpleLogRecordProcessor({ exporter: new OTLPLogExporter({ url: logsEndpoint }) })
    );
  }

  loggerProvider = new LoggerProvider({
    resource,
    processors: logProcessors,
  });
  logs.setGlobalLoggerProvider(loggerProvider);

  initialized = true;
}

/**
 * Flushes and shuts down OpenTelemetry providers cleanly.
 * Idempotent when telemetry is not initialized.
 */
export async function shutdownApiTelemetry(): Promise<void> {
  if (!initialized) {
    return;
  }

  const activeTracerProvider = tracerProvider;
  const activeLoggerProvider = loggerProvider;
  tracerProvider = null;
  loggerProvider = null;
  initialized = false;

  await Promise.all([
    activeTracerProvider?.shutdown(),
    activeLoggerProvider?.shutdown(),
  ]);

  trace.disable();
  context.disable();
  propagation.disable();
  logs.disable();
}

/**
 * Normalizes incoming HTTP headers to lowercase keys so W3C propagation is case-insensitive.
 */
export function normalizeHeaders(headers: IncomingHeaders): Record<string, string> {
  const normalized: Record<string, string> = {};
  if (!headers) {
    return normalized;
  }

  const maybeHeaders = headers as Headers;
  if (typeof maybeHeaders.get === 'function' && typeof maybeHeaders.forEach === 'function') {
    maybeHeaders.forEach((value, key) => {
      normalized[key.toLowerCase()] = value;
    });
    return normalized;
  }

  for (const [key, value] of Object.entries(headers)) {
    if (typeof value === 'string') {
      normalized[key.toLowerCase()] = value;
    } else if (Array.isArray(value) && value.length > 0) {
      normalized[key.toLowerCase()] = value.join(',');
    }
  }
  return normalized;
}

/**
 * Extracts W3C traceparent/tracestate context from incoming HTTP headers.
 */
export function extractTraceContext(headers: IncomingHeaders): Context {
  const carrier = normalizeHeaders(headers);
  return propagation.extract(context.active(), carrier);
}

/**
 * Returns the shared API OpenTelemetry tracer.
 */
export function getApiTracer(): Tracer {
  return trace.getTracer('real-estate-api');
}

/**
 * Emits a trace-correlated OpenTelemetry log record and appends a corresponding span event.
 */
export function logApi(
  severityNumber: SeverityNumber,
  severityText: string,
  message: string,
  attributes?: LogAttributes
): void {
  const activeContext = context.active();
  const activeSpan = trace.getActiveSpan();
  const spanContext = activeSpan?.spanContext() ?? trace.getSpanContext(activeContext);

  activeSpan?.addEvent(message, attributes as Attributes | undefined);

  const enrichedAttributes: LogAttributes = {
    ...attributes,
    ...(spanContext
      ? {
          traceId: spanContext.traceId,
          spanId: spanContext.spanId,
          traceFlags: spanContext.traceFlags,
        }
      : {}),
  };

  logs.getLogger('real-estate-api').emit({
    severityNumber,
    severityText,
    body: message,
    attributes: enrichedAttributes,
    context: activeContext,
  });
}

/**
 * Runs an operation inside an active API span, automatically recording errors and ending the span.
 */
export async function withApiSpan<T>(
  name: string,
  attributes: Attributes,
  fn: (span: Span) => Promise<T> | T
): Promise<T> {
  return getApiTracer().startActiveSpan(name, { attributes }, async (span) => {
    try {
      return await fn(span);
    } catch (err) {
      const error = err instanceof Error ? err : new Error(String(err));
      span.recordException(error);
      span.setStatus({ code: SpanStatusCode.ERROR, message: error.message });
      throw err;
    } finally {
      span.end();
    }
  });
}
