import {
  context,
  propagation,
  trace,
  type Attributes,
  type Context,
  type Tracer,
} from '@opentelemetry/api';
import { logs, SeverityNumber, type LogAttributes } from '@opentelemetry/api-logs';
import { ExportResultCode, W3CTraceContextPropagator, type ExportResult } from '@opentelemetry/core';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { resourceFromAttributes } from '@opentelemetry/resources';
import {
  LoggerProvider,
  SimpleLogRecordProcessor,
  type LogRecordExporter,
  type LogRecordProcessor,
  type ReadableLogRecord,
} from '@opentelemetry/sdk-logs';
import { BatchSpanProcessor, type SpanProcessor } from '@opentelemetry/sdk-trace-base';
import { WebTracerProvider } from '@opentelemetry/sdk-trace-web';
import { ATTR_SERVICE_NAME } from '@opentelemetry/semantic-conventions';

export type UiLogSeverity = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';

const SEVERITY_MAP: Record<UiLogSeverity, SeverityNumber> = {
  DEBUG: SeverityNumber.DEBUG,
  INFO: SeverityNumber.INFO,
  WARN: SeverityNumber.WARN,
  ERROR: SeverityNumber.ERROR,
};

export interface InitUiTelemetryOptions {
  serviceName?: string;
  tracesEndpoint?: string;
  enableNetworkExport?: boolean;
  spanProcessors?: SpanProcessor[];
  logProcessors?: LogRecordProcessor[];
}

/**
 * Exports browser OpenTelemetry log records as single-line structured JSON to the console.
 */
export class JsonBrowserLogRecordExporter implements LogRecordExporter {
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

let tracerProvider: WebTracerProvider | null = null;
let loggerProvider: LoggerProvider | null = null;
let initialized = false;

/**
 * Initializes OpenTelemetry web tracing and structured logging for the UI.
 * Network OTLP trace export is disabled automatically in test mode while keeping
 * W3C traceparent generation active.
 */
export function initUiTelemetry(options: InitUiTelemetryOptions = {}): void {
  if (initialized) {
    return;
  }

  const serviceName =
    options.serviceName ?? (import.meta.env.VITE_OTEL_SERVICE_NAME || 'real-estate-ui');
  const tracesEndpoint =
    options.tracesEndpoint ??
    (import.meta.env.VITE_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT || '/v1/traces');
  const enableNetworkExport = options.enableNetworkExport ?? import.meta.env.MODE !== 'test';

  const resource = resourceFromAttributes({
    [ATTR_SERVICE_NAME]: serviceName,
  });

  const spanProcessors: SpanProcessor[] = [...(options.spanProcessors ?? [])];
  if (enableNetworkExport) {
    spanProcessors.push(
      new BatchSpanProcessor(new OTLPTraceExporter({ url: tracesEndpoint }), {
        scheduledDelayMillis: 250,
      })
    );
  }

  const propagator = new W3CTraceContextPropagator();
  tracerProvider = new WebTracerProvider({
    resource,
    spanProcessors,
  });
  tracerProvider.register({ propagator });
  propagation.setGlobalPropagator(propagator);

  const logProcessors: LogRecordProcessor[] = [
    new SimpleLogRecordProcessor({ exporter: new JsonBrowserLogRecordExporter(serviceName) }),
    ...(options.logProcessors ?? []),
  ];
  loggerProvider = new LoggerProvider({
    resource,
    processors: logProcessors,
  });
  logs.setGlobalLoggerProvider(loggerProvider);

  initialized = true;
}

/**
 * Flushes and shuts down UI OpenTelemetry providers cleanly.
 */
export async function shutdownUiTelemetry(): Promise<void> {
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
 * Returns the shared UI OpenTelemetry tracer.
 */
export function getTracer(): Tracer {
  return trace.getTracer('real-estate-ui');
}

/**
 * Injects W3C traceparent/tracestate headers from the active or given context into a headers object.
 */
export function injectTraceHeaders(
  headers: Record<string, string> = {},
  targetContext: Context = context.active()
): Record<string, string> {
  const carrier: Record<string, string> = { ...headers };
  propagation.inject(targetContext, carrier, {
    set(c, key, value) {
      c[key] = String(value);
    },
  });
  return carrier;
}

/**
 * Emits a trace-correlated browser log record and adds a matching event to the active span.
 */
export function logUi(
  severity: UiLogSeverity,
  message: string,
  attributes?: LogAttributes,
  targetContext?: Context
): void {
  const activeContext = targetContext ?? context.active();
  const activeSpan = trace.getSpan(activeContext) ?? trace.getActiveSpan();
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

  logs.getLogger('real-estate-ui').emit({
    severityNumber: SEVERITY_MAP[severity],
    severityText: severity,
    body: message,
    attributes: enrichedAttributes,
    context: activeContext,
  });
}
