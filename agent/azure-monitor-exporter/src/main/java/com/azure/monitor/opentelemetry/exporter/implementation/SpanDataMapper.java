/*
 * ApplicationInsights-Java
 * Copyright (c) Microsoft Corporation
 * All rights reserved.
 *
 * MIT License
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the ""Software""), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify, merge,
 * publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 * persons to whom the Software is furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package com.azure.monitor.opentelemetry.exporter.implementation;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.azure.core.util.logging.ClientLogger;
import com.azure.monitor.opentelemetry.exporter.implementation.builders.AbstractTelemetryBuilder;
import com.azure.monitor.opentelemetry.exporter.implementation.builders.ExceptionTelemetryBuilder;
import com.azure.monitor.opentelemetry.exporter.implementation.builders.Exceptions;
import com.azure.monitor.opentelemetry.exporter.implementation.builders.MessageTelemetryBuilder;
import com.azure.monitor.opentelemetry.exporter.implementation.builders.RemoteDependencyTelemetryBuilder;
import com.azure.monitor.opentelemetry.exporter.implementation.builders.RequestTelemetryBuilder;
import com.azure.monitor.opentelemetry.exporter.implementation.models.ContextTagKeys;
import com.azure.monitor.opentelemetry.exporter.implementation.models.TelemetryItem;
import com.azure.monitor.opentelemetry.exporter.implementation.utils.FormattedDuration;
import com.azure.monitor.opentelemetry.exporter.implementation.utils.FormattedTime;
import com.azure.monitor.opentelemetry.exporter.implementation.utils.TelemetryUtil;
import com.azure.monitor.opentelemetry.exporter.implementation.utils.Trie;
import com.azure.monitor.opentelemetry.exporter.implementation.utils.UrlParser;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;

// TODO (trask) move this class into internal package
public final class SpanDataMapper {

  private static final ClientLogger LOGGER = new ClientLogger(SpanDataMapper.class);

  private static final Set<String> SQL_DB_SYSTEMS;

  private static final Trie<Boolean> STANDARD_ATTRIBUTE_PREFIX_TRIE;

  // TODO (trask) add to generated ContextTagKeys class
  private static final ContextTagKeys AI_DEVICE_OS = ContextTagKeys.fromString("ai.device.os");

  // TODO (trask) this can go away once new indexer is rolled out to gov clouds
  private static final AttributeKey<List<String>> AI_REQUEST_CONTEXT_KEY =
      AttributeKey.stringArrayKey("http.response.header.request_context");

  public static final AttributeKey<String> AI_OPERATION_NAME_KEY =
      AttributeKey.stringKey("applicationinsights.internal.operation_name");
  public static final AttributeKey<String> AI_LEGACY_PARENT_ID_KEY =
      AttributeKey.stringKey("applicationinsights.internal.legacy_parent_id");
  public static final AttributeKey<String> AI_LEGACY_ROOT_ID_KEY =
      AttributeKey.stringKey("applicationinsights.internal.legacy_root_id");

  // this is only used by the 2.x web interop bridge
  // for ThreadContext.getRequestTelemetryContext().getRequestTelemetry().setSource()
  private static final AttributeKey<String> AI_SPAN_SOURCE_KEY =
      AttributeKey.stringKey("applicationinsights.internal.source");
  private static final AttributeKey<String> AI_SESSION_ID_KEY =
      AttributeKey.stringKey("applicationinsights.internal.session_id");
  private static final AttributeKey<String> AI_DEVICE_OS_KEY =
      AttributeKey.stringKey("applicationinsights.internal.operating_system");
  private static final AttributeKey<String> AI_DEVICE_OS_VERSION_KEY =
      AttributeKey.stringKey("applicationinsights.internal.operating_system_version");

  private static final AttributeKey<String> AZURE_NAMESPACE =
      AttributeKey.stringKey("az.namespace");
  private static final AttributeKey<String> AZURE_SDK_PEER_ADDRESS =
      AttributeKey.stringKey("peer.address");
  private static final AttributeKey<String> AZURE_SDK_MESSAGE_BUS_DESTINATION =
      AttributeKey.stringKey("message_bus.destination");
  private static final AttributeKey<Long> AZURE_SDK_ENQUEUED_TIME =
      AttributeKey.longKey("x-opt-enqueued-time");

  private static final AttributeKey<Long> KAFKA_RECORD_QUEUE_TIME_MS =
      AttributeKey.longKey("kafka.record.queue_time_ms");
  private static final AttributeKey<Long> KAFKA_OFFSET = AttributeKey.longKey("kafka.offset");

  static {
    Set<String> dbSystems = new HashSet<>();
    dbSystems.add(SemanticAttributes.DbSystemValues.DB2);
    dbSystems.add(SemanticAttributes.DbSystemValues.DERBY);
    dbSystems.add(SemanticAttributes.DbSystemValues.MARIADB);
    dbSystems.add(SemanticAttributes.DbSystemValues.MSSQL);
    dbSystems.add(SemanticAttributes.DbSystemValues.MYSQL);
    dbSystems.add(SemanticAttributes.DbSystemValues.ORACLE);
    dbSystems.add(SemanticAttributes.DbSystemValues.POSTGRESQL);
    dbSystems.add(SemanticAttributes.DbSystemValues.SQLITE);
    dbSystems.add(SemanticAttributes.DbSystemValues.OTHER_SQL);
    dbSystems.add(SemanticAttributes.DbSystemValues.HSQLDB);
    dbSystems.add(SemanticAttributes.DbSystemValues.H2);

    SQL_DB_SYSTEMS = Collections.unmodifiableSet(dbSystems);

    // TODO need to keep this list in sync as new semantic conventions are defined
    STANDARD_ATTRIBUTE_PREFIX_TRIE =
        Trie.<Boolean>newBuilder()
            .put("http.", true)
            .put("db.", true)
            .put("message.", true)
            .put("messaging.", true)
            .put("rpc.", true)
            .put("enduser.", true)
            .put("net.", true)
            .put("peer.", true)
            .put("exception.", true)
            .put("thread.", true)
            .put("faas.", true)
            .put("code.", true)
            .build();
  }

  private final boolean captureHttpServer4xxAsError;
  private final Consumer<AbstractTelemetryBuilder> telemetryInitializer;
  private final BiPredicate<EventData, String> eventSuppressor;
  private final Supplier<String> appIdSupplier;

  public SpanDataMapper(
      boolean captureHttpServer4xxAsError,
      Consumer<AbstractTelemetryBuilder> telemetryInitializer,
      BiPredicate<EventData, String> eventSuppressor,
      Supplier<String> appIdSupplier) {
    this.captureHttpServer4xxAsError = captureHttpServer4xxAsError;
    this.telemetryInitializer = telemetryInitializer;
    this.eventSuppressor = eventSuppressor;
    this.appIdSupplier = appIdSupplier;
  }

  public TelemetryItem map(SpanData span) {
    float samplingPercentage = getSamplingPercentage(span.getSpanContext().getTraceState());
    return map(span, samplingPercentage);
  }

  public void map(SpanData span, Consumer<TelemetryItem> consumer) {
    float samplingPercentage = getSamplingPercentage(span.getSpanContext().getTraceState());
    TelemetryItem telemetryItem = map(span, samplingPercentage);
    consumer.accept(telemetryItem);
    exportEvents(
        span,
        telemetryItem.getTags().get(ContextTagKeys.AI_OPERATION_NAME.toString()),
        samplingPercentage,
        consumer);
  }

  public TelemetryItem map(SpanData span, float samplingPercentage) {
    SpanKind kind = span.getKind();
    String instrumentationName = span.getInstrumentationScopeInfo().getName();
    TelemetryItem telemetryItem;
    if (kind == SpanKind.INTERNAL) {
      if (instrumentationName.startsWith("io.opentelemetry.spring-scheduling-")
          && !span.getParentSpanContext().isValid()) {
        // TODO (trask) AI mapping: need semantic convention for determining whether to map INTERNAL
        // to request or dependency (or need clarification to use SERVER for this)
        telemetryItem = exportRequest(span, samplingPercentage);
      } else {
        telemetryItem = exportRemoteDependency(span, true, samplingPercentage);
      }
    } else if (kind == SpanKind.CLIENT || kind == SpanKind.PRODUCER) {
      telemetryItem = exportRemoteDependency(span, false, samplingPercentage);
    } else if (kind == SpanKind.CONSUMER
        && "receive".equals(span.getAttributes().get(SemanticAttributes.MESSAGING_OPERATION))) {
      telemetryItem = exportRemoteDependency(span, false, samplingPercentage);
    } else if (kind == SpanKind.SERVER || kind == SpanKind.CONSUMER) {
      telemetryItem = exportRequest(span, samplingPercentage);
    } else {
      throw new UnsupportedOperationException(kind.name());
    }
    return telemetryItem;
  }

  private TelemetryItem exportRemoteDependency(
      SpanData span, boolean inProc, float samplingPercentage) {
    RemoteDependencyTelemetryBuilder telemetryBuilder = RemoteDependencyTelemetryBuilder.create();
    telemetryInitializer.accept(telemetryBuilder);

    // set standard properties
    setOperationTags(telemetryBuilder, span);
    setTime(telemetryBuilder, span.getStartEpochNanos());
    setSampleRate(telemetryBuilder, samplingPercentage);
    setExtraAttributes(telemetryBuilder, span.getAttributes());
    addLinks(telemetryBuilder, span.getLinks());

    // set dependency-specific properties
    telemetryBuilder.setId(span.getSpanId());
    telemetryBuilder.setName(getDependencyName(span));
    telemetryBuilder.setDuration(
        FormattedDuration.fromNanos(span.getEndEpochNanos() - span.getStartEpochNanos()));
    telemetryBuilder.setSuccess(getSuccess(span));

    if (inProc) {
      telemetryBuilder.setType("InProc");
    } else {
      applySemanticConventions(telemetryBuilder, span);
    }

    return telemetryBuilder.build();
  }

  private static final Set<String> DEFAULT_HTTP_SPAN_NAMES =
      new HashSet<>(
          Arrays.asList(
              "HTTP OPTIONS",
              "HTTP GET",
              "HTTP HEAD",
              "HTTP POST",
              "HTTP PUT",
              "HTTP DELETE",
              "HTTP TRACE",
              "HTTP CONNECT",
              "HTTP PATCH"));

  // the backend product prefers more detailed (but possibly infinite cardinality) name for http
  // dependencies
  private static String getDependencyName(SpanData span) {
    String name = span.getName();

    String method = span.getAttributes().get(SemanticAttributes.HTTP_METHOD);
    if (method == null) {
      return name;
    }

    if (!DEFAULT_HTTP_SPAN_NAMES.contains(name)) {
      return name;
    }

    String url = span.getAttributes().get(SemanticAttributes.HTTP_URL);
    if (url == null) {
      return name;
    }

    String path = UrlParser.getPathFromUrl(url);
    if (path == null) {
      return name;
    }
    return path.isEmpty() ? method + " /" : method + " " + path;
  }

  private void applySemanticConventions(
      RemoteDependencyTelemetryBuilder telemetryBuilder, SpanData span) {
    Attributes attributes = span.getAttributes();
    String httpMethod = attributes.get(SemanticAttributes.HTTP_METHOD);
    if (httpMethod != null) {
      applyHttpClientSpan(telemetryBuilder, attributes);
      return;
    }
    String rpcSystem = attributes.get(SemanticAttributes.RPC_SYSTEM);
    if (rpcSystem != null) {
      applyRpcClientSpan(telemetryBuilder, rpcSystem, attributes);
      return;
    }
    String dbSystem = attributes.get(SemanticAttributes.DB_SYSTEM);
    if (dbSystem != null) {
      applyDatabaseClientSpan(telemetryBuilder, dbSystem, attributes);
      return;
    }
    String messagingSystem = getMessagingSystem(attributes);
    if (messagingSystem != null) {
      applyMessagingClientSpan(telemetryBuilder, span.getKind(), messagingSystem, attributes);
      return;
    }

    // passing max value because we don't know what the default port would be in this case,
    // so we always want the port included
    String target = getTargetFromPeerAttributes(attributes, Integer.MAX_VALUE);
    if (target != null) {
      telemetryBuilder.setTarget(target);
      return;
    }

    // with no target, the App Map falls back to creating a node based on the telemetry name,
    // which is very confusing, e.g. when multiple unrelated nodes all point to a single node
    // because they had dependencies with the same telemetry name
    //
    // so we mark these as InProc, even though they aren't INTERNAL spans,
    // in order to prevent App Map from considering them
    telemetryBuilder.setType("InProc");
  }

  @Nullable
  private static String getMessagingSystem(Attributes attributes) {
    String azureNamespace = attributes.get(AZURE_NAMESPACE);
    if (isAzureSdkMessaging(azureNamespace)) {
      // special case needed until Azure SDK moves to OTel semantic conventions
      return azureNamespace;
    }
    return attributes.get(SemanticAttributes.MESSAGING_SYSTEM);
  }

  private static void setOperationTags(AbstractTelemetryBuilder telemetryBuilder, SpanData span) {
    setOperationId(telemetryBuilder, span.getTraceId());
    setOperationParentId(telemetryBuilder, span.getParentSpanContext().getSpanId());
    setOperationName(telemetryBuilder, span.getAttributes());
  }

  private static void setOperationId(AbstractTelemetryBuilder telemetryBuilder, String traceId) {
    telemetryBuilder.addTag(ContextTagKeys.AI_OPERATION_ID.toString(), traceId);
  }

  private static void setOperationParentId(
      AbstractTelemetryBuilder telemetryBuilder, String parentSpanId) {
    if (SpanId.isValid(parentSpanId)) {
      telemetryBuilder.addTag(ContextTagKeys.AI_OPERATION_PARENT_ID.toString(), parentSpanId);
    }
  }

  private static void setOperationName(
      AbstractTelemetryBuilder telemetryBuilder, Attributes attributes) {
    String operationName = attributes.get(AI_OPERATION_NAME_KEY);
    if (operationName != null) {
      setOperationName(telemetryBuilder, operationName);
    }
  }

  private static void setOperationName(
      AbstractTelemetryBuilder telemetryBuilder, String operationName) {
    telemetryBuilder.addTag(ContextTagKeys.AI_OPERATION_NAME.toString(), operationName);
  }

  private void applyHttpClientSpan(
      RemoteDependencyTelemetryBuilder telemetryBuilder, Attributes attributes) {

    String target = getTargetForHttpClientSpan(attributes);

    String targetAppId = getTargetAppId(attributes);

    if (targetAppId == null || targetAppId.equals(appIdSupplier.get())) {
      telemetryBuilder.setType("Http");
      telemetryBuilder.setTarget(target);
    } else {
      // using "Http (tracked component)" is important for dependencies that go cross-component
      // (have an appId in their target field)
      // if you use just HTTP, Breeze will remove appid from the target
      // TODO (trask) remove this once confirmed by zakima that it is no longer needed
      telemetryBuilder.setType("Http (tracked component)");
      telemetryBuilder.setTarget(target + " | " + targetAppId);
    }

    Long httpStatusCode = attributes.get(SemanticAttributes.HTTP_STATUS_CODE);
    if (httpStatusCode != null) {
      telemetryBuilder.setResultCode(Long.toString(httpStatusCode));
    }

    String url = attributes.get(SemanticAttributes.HTTP_URL);
    telemetryBuilder.setData(url);
  }

  @Nullable
  private static String getTargetAppId(Attributes attributes) {
    List<String> requestContextList = attributes.get(AI_REQUEST_CONTEXT_KEY);
    if (requestContextList == null || requestContextList.isEmpty()) {
      return null;
    }
    String requestContext = requestContextList.get(0);
    int index = requestContext.indexOf('=');
    if (index == -1) {
      return null;
    }
    return requestContext.substring(index + 1);
  }

  private static String getTargetForHttpClientSpan(Attributes attributes) {
    // from the spec, at least one of the following sets of attributes is required:
    // * http.url
    // * http.scheme, http.host, http.target
    // * http.scheme, net.peer.name, net.peer.port, http.target
    // * http.scheme, net.peer.ip, net.peer.port, http.target
    String target = getTargetFromPeerService(attributes);
    if (target != null) {
      return target;
    }
    // note http.host includes the port (at least when non-default)
    target = attributes.get(SemanticAttributes.HTTP_HOST);
    if (target != null) {
      String scheme = attributes.get(SemanticAttributes.HTTP_SCHEME);
      if ("http".equals(scheme)) {
        if (target.endsWith(":80")) {
          target = target.substring(0, target.length() - 3);
        }
      } else if ("https".equals(scheme)) {
        if (target.endsWith(":443")) {
          target = target.substring(0, target.length() - 4);
        }
      }
      return target;
    }
    String url = attributes.get(SemanticAttributes.HTTP_URL);
    if (url != null) {
      target = UrlParser.getTargetFromUrl(url);
      if (target != null) {
        return target;
      }
    }
    String scheme = attributes.get(SemanticAttributes.HTTP_SCHEME);
    int defaultPort;
    if ("http".equals(scheme)) {
      defaultPort = 80;
    } else if ("https".equals(scheme)) {
      defaultPort = 443;
    } else {
      defaultPort = 0;
    }
    target = getTargetFromNetAttributes(attributes, defaultPort);
    if (target != null) {
      return target;
    }
    // this should not happen, just a failsafe
    return "Http";
  }

  @Nullable
  private static String getTargetFromPeerAttributes(Attributes attributes, int defaultPort) {
    String target = getTargetFromPeerService(attributes);
    if (target != null) {
      return target;
    }
    return getTargetFromNetAttributes(attributes, defaultPort);
  }

  @Nullable
  private static String getTargetFromPeerService(Attributes attributes) {
    // do not append port to peer.service
    return attributes.get(SemanticAttributes.PEER_SERVICE);
  }

  @Nullable
  private static String getTargetFromNetAttributes(Attributes attributes, int defaultPort) {
    String target = getHostFromNetAttributes(attributes);
    if (target == null) {
      return null;
    }
    // append net.peer.port to target
    Long port = attributes.get(SemanticAttributes.NET_PEER_PORT);
    if (port != null && port != defaultPort) {
      return target + ":" + port;
    }
    return target;
  }

  @Nullable
  private static String getHostFromNetAttributes(Attributes attributes) {
    String host = attributes.get(SemanticAttributes.NET_PEER_NAME);
    if (host != null) {
      return host;
    }
    return attributes.get(SemanticAttributes.NET_PEER_IP);
  }

  private static void applyRpcClientSpan(
      RemoteDependencyTelemetryBuilder telemetryBuilder, String rpcSystem, Attributes attributes) {
    telemetryBuilder.setType(rpcSystem);
    String target = getTargetFromPeerAttributes(attributes, 0);
    // not appending /rpc.service for now since that seems too fine-grained
    if (target == null) {
      target = rpcSystem;
    }
    telemetryBuilder.setTarget(target);
  }

  private static void applyDatabaseClientSpan(
      RemoteDependencyTelemetryBuilder telemetryBuilder, String dbSystem, Attributes attributes) {
    String dbStatement = attributes.get(SemanticAttributes.DB_STATEMENT);
    if (dbStatement == null) {
      dbStatement = attributes.get(SemanticAttributes.DB_OPERATION);
    }
    String type;
    if (SQL_DB_SYSTEMS.contains(dbSystem)) {
      if (dbSystem.equals(SemanticAttributes.DbSystemValues.MYSQL)) {
        type = "mysql"; // this has special icon in portal
      } else if (dbSystem.equals(SemanticAttributes.DbSystemValues.POSTGRESQL)) {
        type = "postgresql"; // this has special icon in portal
      } else {
        type = "SQL";
      }
    } else {
      type = dbSystem;
    }
    telemetryBuilder.setType(type);
    telemetryBuilder.setData(dbStatement);
    String target =
        nullAwareConcat(
            getTargetFromPeerAttributes(attributes, getDefaultPortForDbSystem(dbSystem)),
            attributes.get(SemanticAttributes.DB_NAME),
            " | ");
    if (target == null) {
      target = dbSystem;
    }
    telemetryBuilder.setTarget(target);
  }

  private static void applyMessagingClientSpan(
      RemoteDependencyTelemetryBuilder telemetryBuilder,
      SpanKind spanKind,
      String messagingSystem,
      Attributes attributes) {
    if (spanKind == SpanKind.PRODUCER) {
      telemetryBuilder.setType("Queue Message | " + messagingSystem);
    } else {
      // e.g. CONSUMER kind (without remote parent) and CLIENT kind
      telemetryBuilder.setType(messagingSystem);
    }
    telemetryBuilder.setTarget(getMessagingTargetSource(attributes));
  }

  private static int getDefaultPortForDbSystem(String dbSystem) {
    // jdbc default ports are from
    // io.opentelemetry.javaagent.instrumentation.jdbc.JdbcConnectionUrlParser
    // TODO (trask) make the ports constants (at least in JdbcConnectionUrlParser) so they can be
    // used here
    switch (dbSystem) {
      case SemanticAttributes.DbSystemValues.MONGODB:
        return 27017;
      case SemanticAttributes.DbSystemValues.CASSANDRA:
        return 9042;
      case SemanticAttributes.DbSystemValues.REDIS:
        return 6379;
      case SemanticAttributes.DbSystemValues.MARIADB:
      case SemanticAttributes.DbSystemValues.MYSQL:
        return 3306;
      case SemanticAttributes.DbSystemValues.MSSQL:
        return 1433;
      case SemanticAttributes.DbSystemValues.DB2:
        return 50000;
      case SemanticAttributes.DbSystemValues.ORACLE:
        return 1521;
      case SemanticAttributes.DbSystemValues.H2:
        return 8082;
      case SemanticAttributes.DbSystemValues.DERBY:
        return 1527;
      case SemanticAttributes.DbSystemValues.POSTGRESQL:
        return 5432;
      default:
        return 0;
    }
  }

  private TelemetryItem exportRequest(SpanData span, float samplingPercentage) {
    RequestTelemetryBuilder telemetryBuilder = RequestTelemetryBuilder.create();
    telemetryInitializer.accept(telemetryBuilder);

    Attributes attributes = span.getAttributes();
    long startEpochNanos = span.getStartEpochNanos();

    // set standard properties
    telemetryBuilder.setId(span.getSpanId());
    setTime(telemetryBuilder, startEpochNanos);
    setSampleRate(telemetryBuilder, samplingPercentage);
    setExtraAttributes(telemetryBuilder, attributes);
    addLinks(telemetryBuilder, span.getLinks());

    String operationName = getOperationName(span);
    telemetryBuilder.addTag(ContextTagKeys.AI_OPERATION_NAME.toString(), operationName);
    telemetryBuilder.addTag(ContextTagKeys.AI_OPERATION_ID.toString(), span.getTraceId());

    // see behavior specified at https://github.com/microsoft/ApplicationInsights-Java/issues/1174
    String aiLegacyParentId = span.getAttributes().get(AI_LEGACY_PARENT_ID_KEY);
    if (aiLegacyParentId != null) {
      // this was the real (legacy) parent id, but it didn't fit span id format
      telemetryBuilder.addTag(ContextTagKeys.AI_OPERATION_PARENT_ID.toString(), aiLegacyParentId);
    } else if (span.getParentSpanContext().isValid()) {
      telemetryBuilder.addTag(
          ContextTagKeys.AI_OPERATION_PARENT_ID.toString(),
          span.getParentSpanContext().getSpanId());
    }
    String aiLegacyRootId = span.getAttributes().get(AI_LEGACY_ROOT_ID_KEY);
    if (aiLegacyRootId != null) {
      telemetryBuilder.addTag("ai_legacyRootID", aiLegacyRootId);
    }

    // set request-specific properties
    telemetryBuilder.setName(operationName);
    telemetryBuilder.setDuration(
        FormattedDuration.fromNanos(span.getEndEpochNanos() - startEpochNanos));
    telemetryBuilder.setSuccess(getSuccess(span));

    String httpUrl = getHttpUrlFromServerSpan(attributes);
    if (httpUrl != null) {
      telemetryBuilder.setUrl(httpUrl);
    }

    Long httpStatusCode = attributes.get(SemanticAttributes.HTTP_STATUS_CODE);
    if (httpStatusCode == null) {
      httpStatusCode = attributes.get(SemanticAttributes.RPC_GRPC_STATUS_CODE);
    }
    if (httpStatusCode != null) {
      telemetryBuilder.setResponseCode(Long.toString(httpStatusCode));
    } else {
      telemetryBuilder.setResponseCode("0");
    }

    String locationIp = attributes.get(SemanticAttributes.HTTP_CLIENT_IP);
    if (locationIp == null) {
      // only use net.peer.ip if http.client_ip is not available
      locationIp = attributes.get(SemanticAttributes.NET_PEER_IP);
    }
    if (locationIp != null) {
      telemetryBuilder.addTag(ContextTagKeys.AI_LOCATION_IP.toString(), locationIp);
    }

    telemetryBuilder.setSource(getSource(attributes, span.getSpanContext()));

    String sessionId = attributes.get(AI_SESSION_ID_KEY);
    if (sessionId != null) {
      // this is only used by the 2.x web interop bridge for
      // ThreadContext.getRequestTelemetryContext().getHttpRequestTelemetry().getContext().getSession().setId()
      telemetryBuilder.addTag(ContextTagKeys.AI_SESSION_ID.toString(), sessionId);
    }
    String deviceOs = attributes.get(AI_DEVICE_OS_KEY);
    if (deviceOs != null) {
      // this is only used by the 2.x web interop bridge for
      // ThreadContext.getRequestTelemetryContext().getHttpRequestTelemetry().getContext().getDevice().setOperatingSystem()
      telemetryBuilder.addTag(AI_DEVICE_OS.toString(), deviceOs);
    }
    String deviceOsVersion = attributes.get(AI_DEVICE_OS_VERSION_KEY);
    if (deviceOsVersion != null) {
      // this is only used by the 2.x web interop bridge for
      // ThreadContext.getRequestTelemetryContext().getHttpRequestTelemetry().getContext().getDevice().setOperatingSystemVersion()
      telemetryBuilder.addTag(ContextTagKeys.AI_DEVICE_OS_VERSION.toString(), deviceOsVersion);
    }

    // TODO(trask)? for batch consumer, enqueuedTime should be the average of this attribute
    //  across all links
    Long enqueuedTime = attributes.get(AZURE_SDK_ENQUEUED_TIME);
    if (enqueuedTime != null) {
      long timeSinceEnqueuedMillis =
          Math.max(
              0L, NANOSECONDS.toMillis(span.getStartEpochNanos()) - SECONDS.toMillis(enqueuedTime));
      telemetryBuilder.addMeasurement("timeSinceEnqueued", (double) timeSinceEnqueuedMillis);
    }
    Long timeSinceEnqueuedMillis = attributes.get(KAFKA_RECORD_QUEUE_TIME_MS);
    if (timeSinceEnqueuedMillis != null) {
      telemetryBuilder.addMeasurement("timeSinceEnqueued", (double) timeSinceEnqueuedMillis);
    }

    return telemetryBuilder.build();
  }

  private boolean getSuccess(SpanData span) {
    switch (span.getStatus().getStatusCode()) {
      case ERROR:
        return false;
      case OK:
        // instrumentation never sets OK, so this is explicit user override
        return true;
      case UNSET:
        if (captureHttpServer4xxAsError) {
          Long statusCode = span.getAttributes().get(SemanticAttributes.HTTP_STATUS_CODE);
          return statusCode == null || statusCode < 400;
        }
        return true;
    }
    return true;
  }

  @Nullable
  public static String getHttpUrlFromServerSpan(Attributes attributes) {
    String httpUrl = attributes.get(SemanticAttributes.HTTP_URL);
    if (httpUrl != null) {
      return httpUrl;
    }
    String scheme = attributes.get(SemanticAttributes.HTTP_SCHEME);
    if (scheme == null) {
      return null;
    }
    String host = attributes.get(SemanticAttributes.HTTP_HOST);
    if (host == null) {
      return null;
    }
    String target = attributes.get(SemanticAttributes.HTTP_TARGET);
    if (target == null) {
      return null;
    }
    return scheme + "://" + host + target;
  }

  @Nullable
  private String getSource(Attributes attributes, @Nullable SpanContext spanContext) {
    // this is only used by the 2.x web interop bridge
    // for ThreadContext.getRequestTelemetryContext().getHttpRequestTelemetry().setSource()
    String source = attributes.get(AI_SPAN_SOURCE_KEY);
    if (source != null) {
      return source;
    }
    if (spanContext != null) {
      source = spanContext.getTraceState().get("az");
    }
    if (source != null && !source.equals(appIdSupplier.get())) {
      return source;
    }
    return getMessagingTargetSource(attributes);
  }

  @Nullable
  private static String getMessagingTargetSource(Attributes attributes) {
    if (isAzureSdkMessaging(attributes.get(AZURE_NAMESPACE))) {
      // special case needed until Azure SDK moves to OTel semantic conventions
      String peerAddress = attributes.get(AZURE_SDK_PEER_ADDRESS);
      String destination = attributes.get(AZURE_SDK_MESSAGE_BUS_DESTINATION);
      return peerAddress + "/" + destination;
    }
    String messagingSystem = attributes.get(SemanticAttributes.MESSAGING_SYSTEM);
    if (messagingSystem == null) {
      return null;
    }
    // TODO (trask) AI mapping: should this pass default port for messaging.system?
    String source =
        nullAwareConcat(
            getTargetFromPeerAttributes(attributes, 0),
            attributes.get(SemanticAttributes.MESSAGING_DESTINATION),
            "/");
    if (source != null) {
      return source;
    }
    // fallback
    return messagingSystem;
  }

  private static boolean isAzureSdkMessaging(String messagingSystem) {
    return "Microsoft.EventHub".equals(messagingSystem)
        || "Microsoft.ServiceBus".equals(messagingSystem);
  }

  private static String getOperationName(SpanData span) {
    String operationName = span.getAttributes().get(AI_OPERATION_NAME_KEY);
    if (operationName != null) {
      return operationName;
    }

    String spanName = span.getName();
    String httpMethod = span.getAttributes().get(SemanticAttributes.HTTP_METHOD);
    if (httpMethod != null && !httpMethod.isEmpty() && spanName.startsWith("/")) {
      return httpMethod + " " + spanName;
    }
    return spanName;
  }

  private static String nullAwareConcat(
      @Nullable String str1, @Nullable String str2, String separator) {
    if (str1 == null) {
      return str2;
    }
    if (str2 == null) {
      return str1;
    }
    return str1 + separator + str2;
  }

  private void exportEvents(
      SpanData span,
      @Nullable String operationName,
      float samplingPercentage,
      Consumer<TelemetryItem> consumer) {
    for (EventData event : span.getEvents()) {
      String instrumentationScopeName = span.getInstrumentationScopeInfo().getName();
      if (eventSuppressor.test(event, instrumentationScopeName)) {
        continue;
      }

      if (event.getAttributes().get(SemanticAttributes.EXCEPTION_TYPE) != null
          || event.getAttributes().get(SemanticAttributes.EXCEPTION_MESSAGE) != null) {
        // TODO (trask) map OpenTelemetry exception to Application Insights exception better
        String stacktrace = event.getAttributes().get(SemanticAttributes.EXCEPTION_STACKTRACE);
        if (stacktrace != null) {
          consumer.accept(
              createExceptionTelemetryItem(stacktrace, span, operationName, samplingPercentage));
        }
        return;
      }

      MessageTelemetryBuilder telemetryBuilder = MessageTelemetryBuilder.create();
      telemetryInitializer.accept(telemetryBuilder);

      // set standard properties
      setOperationId(telemetryBuilder, span.getTraceId());
      setOperationParentId(telemetryBuilder, span.getSpanId());
      if (operationName != null) {
        setOperationName(telemetryBuilder, operationName);
      } else {
        setOperationName(telemetryBuilder, span.getAttributes());
      }
      setTime(telemetryBuilder, event.getEpochNanos());
      setExtraAttributes(telemetryBuilder, event.getAttributes());
      setSampleRate(telemetryBuilder, samplingPercentage);

      // set message-specific properties
      telemetryBuilder.setMessage(event.getName());

      consumer.accept(telemetryBuilder.build());
    }
  }

  private TelemetryItem createExceptionTelemetryItem(
      String errorStack, SpanData span, @Nullable String operationName, float samplingPercentage) {

    ExceptionTelemetryBuilder telemetryBuilder = ExceptionTelemetryBuilder.create();
    telemetryInitializer.accept(telemetryBuilder);

    // set standard properties
    setOperationId(telemetryBuilder, span.getTraceId());
    setOperationParentId(telemetryBuilder, span.getSpanId());
    if (operationName != null) {
      setOperationName(telemetryBuilder, operationName);
    } else {
      setOperationName(telemetryBuilder, span.getAttributes());
    }
    setTime(telemetryBuilder, span.getEndEpochNanos());
    setSampleRate(telemetryBuilder, samplingPercentage);

    // set exception-specific properties
    telemetryBuilder.setExceptions(Exceptions.minimalParse(errorStack));

    return telemetryBuilder.build();
  }

  private static void setTime(AbstractTelemetryBuilder telemetryBuilder, long epochNanos) {
    telemetryBuilder.setTime(FormattedTime.offSetDateTimeFromEpochNanos(epochNanos));
  }

  private static void setSampleRate(
      AbstractTelemetryBuilder telemetryBuilder, float samplingPercentage) {
    if (samplingPercentage != 100) {
      telemetryBuilder.setSampleRate(samplingPercentage);
    }
  }

  private static float getSamplingPercentage(TraceState traceState) {
    return TelemetryUtil.getSamplingPercentage(traceState, 100, true);
  }

  private static void addLinks(AbstractTelemetryBuilder telemetryBuilder, List<LinkData> links) {
    if (links.isEmpty()) {
      return;
    }
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    boolean first = true;
    for (LinkData link : links) {
      if (!first) {
        sb.append(",");
      }
      sb.append("{\"operation_Id\":\"");
      sb.append(link.getSpanContext().getTraceId());
      sb.append("\",\"id\":\"");
      sb.append(link.getSpanContext().getSpanId());
      sb.append("\"}");
      first = false;
    }
    sb.append("]");
    telemetryBuilder.addProperty("_MS.links", sb.toString());
  }

  private static void setExtraAttributes(
      AbstractTelemetryBuilder telemetryBuilder, Attributes attributes) {
    attributes.forEach(
        (key, value) -> {
          String stringKey = key.getKey();
          if (stringKey.startsWith("applicationinsights.internal.")) {
            return;
          }
          if (stringKey.equals(AZURE_NAMESPACE.getKey())
              || stringKey.equals(AZURE_SDK_MESSAGE_BUS_DESTINATION.getKey())
              || stringKey.equals(AZURE_SDK_ENQUEUED_TIME.getKey())) {
            // these are from azure SDK (AZURE_SDK_PEER_ADDRESS gets filtered out automatically
            // since it uses the otel "peer." prefix)
            return;
          }
          if (stringKey.equals(KAFKA_RECORD_QUEUE_TIME_MS.getKey())
              || stringKey.equals(KAFKA_OFFSET.getKey())) {
            return;
          }
          if (stringKey.equals(AI_REQUEST_CONTEXT_KEY.getKey())) {
            return;
          }
          // special case mappings
          if (stringKey.equals(SemanticAttributes.ENDUSER_ID.getKey()) && value instanceof String) {
            telemetryBuilder.addTag(ContextTagKeys.AI_USER_ID.toString(), (String) value);
            return;
          }
          if (stringKey.equals(SemanticAttributes.HTTP_USER_AGENT.getKey())
              && value instanceof String) {
            telemetryBuilder.addTag("ai.user.userAgent", (String) value);
            return;
          }
          if (stringKey.equals("ai.preview.instrumentation_key") && value instanceof String) {
            telemetryBuilder.setInstrumentationKey((String) value);
            return;
          }
          if (stringKey.equals("ai.preview.service_name") && value instanceof String) {
            telemetryBuilder.addTag(ContextTagKeys.AI_CLOUD_ROLE.toString(), (String) value);
            return;
          }
          if (stringKey.equals("ai.preview.service_instance_id") && value instanceof String) {
            telemetryBuilder.addTag(
                ContextTagKeys.AI_CLOUD_ROLE_INSTANCE.toString(), (String) value);
            return;
          }
          if (stringKey.equals("ai.preview.service_version") && value instanceof String) {
            telemetryBuilder.addTag(ContextTagKeys.AI_APPLICATION_VER.toString(), (String) value);
            return;
          }
          if (STANDARD_ATTRIBUTE_PREFIX_TRIE.getOrDefault(stringKey, false)
              && !stringKey.startsWith("http.request.header.")
              && !stringKey.startsWith("http.response.header.")) {
            return;
          }
          String val = convertToString(value, key.getType());
          if (value != null) {
            telemetryBuilder.addProperty(key.getKey(), val);
          }
        });
  }

  @Nullable
  public static String convertToString(Object value, AttributeType type) {
    switch (type) {
      case STRING:
      case BOOLEAN:
      case LONG:
      case DOUBLE:
        return String.valueOf(value);
      case STRING_ARRAY:
      case BOOLEAN_ARRAY:
      case LONG_ARRAY:
      case DOUBLE_ARRAY:
        return join((List<?>) value);
    }
    LOGGER.warning("unexpected attribute type: {}", type);
    return null;
  }

  private static <T> String join(List<T> values) {
    StringBuilder sb = new StringBuilder();
    for (Object val : values) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(val);
    }
    return sb.toString();
  }
}