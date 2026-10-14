package cn.panda.pandagpt.ai;

import cn.panda.pandagpt.config.Provider;
import cn.panda.pandagpt.config.Settings;
import cn.panda.pandagpt.i18n.LocalizedIOException;
import com.google.gson.JsonParseException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 基于 JDK HttpClient 的模型请求实现，每个配置版本复用一个客户端。
 *
 * <p>整个请求（含重试与响应体读取）共享一个总超时；只对限流与网关类错误重试，重试只会重新获取模型回复，不会重复执行工具。
 * 线程：由 AI 工作线程调用，实例可并发使用。
 */
public final class HttpAiProvider implements AiProvider {
    // 这些状态表示请求未被处理，可以安全重试。
    private static final Set<Integer> RETRYABLE = Set.of(429, 502, 503, 504);
    private static final long MAX_BACKOFF_MILLIS = 5000;

    private final Settings settings;
    private final HttpClient client;
    private final WireFormat format;

    public HttpAiProvider(Settings settings) {
        this(settings, newClient(settings));
    }

    HttpAiProvider(Settings settings, HttpClient client) {
        this.settings = settings;
        this.client = client;
        this.format = WireFormat.of(settings.provider());
    }

    /** 按配置的超时与代理创建 HTTP 客户端；不跟随跳转。插件的其他联网功能（例如下载官方纹理）也复用它。 */
    public static HttpClient newClient(Settings settings) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER);
        Settings.Proxy proxy = settings.proxy();
        if (proxy.enabled()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.host(), proxy.port())));
            if (!proxy.username().isBlank()) {
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        // 只把凭据交给配置的代理，绝不发送给目标服务器。
                        if (getRequestorType() == RequestorType.PROXY
                                && proxy.host().equalsIgnoreCase(getRequestingHost())
                                && proxy.port() == getRequestingPort()) {
                            return new PasswordAuthentication(
                                    proxy.username(), proxy.password().toCharArray());
                        }
                        return null;
                    }
                });
            }
        }
        return builder.build();
    }

    /**
     * JDK 默认禁止在 HTTPS 隧道上使用 Basic 代理认证（系统属性 jdk.http.auth.tunneling.disabledSchemes）。
     *
     * @return 配置了代理账号但该限制仍然生效时返回 true
     */
    public static boolean proxyBasicAuthBlocked(Settings settings) {
        Settings.Proxy proxy = settings.proxy();
        if (!proxy.enabled() || proxy.username().isBlank()) {
            return false;
        }
        String disabled = System.getProperty("jdk.http.auth.tunneling.disabledSchemes");
        // 未设置该属性时 JDK 使用 net.properties 中的默认值 Basic。
        return disabled == null || disabled.toLowerCase(java.util.Locale.ROOT).contains("basic");
    }

    @Override
    public AiResponse generate(AiRequest request) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(format.uri(settings))
                .timeout(settings.requestTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(format.body(settings, request), StandardCharsets.UTF_8));
        format.authorize(builder, settings);
        HttpRequest http = builder.build();
        long deadline = System.nanoTime() + settings.requestTimeout().toNanos();
        for (int attempt = 0; ; attempt++) {
            Attempt result = send(http, deadline);
            boolean lastAttempt = attempt >= settings.retries();
            if (result.response != null) {
                int status = result.response.statusCode();
                if (status >= 200 && status < 300) {
                    return parseResponse(
                            settings.provider(), new String(result.response.body(), StandardCharsets.UTF_8));
                }
                if (lastAttempt || !RETRYABLE.contains(status) || !backoff(result.response, attempt, deadline)) {
                    throw statusError(status);
                }
            } else if (lastAttempt || !result.retryable || !backoff(null, attempt, deadline)) {
                throw result.failure;
            }
        }
    }

    private record Attempt(HttpResponse<byte[]> response, IOException failure, boolean retryable) {}

    private Attempt send(HttpRequest request, long deadline) throws InterruptedException {
        CompletableFuture<HttpResponse<byte[]>> future =
                client.sendAsync(request, info -> new LimitedBody(settings.maxResponseBytes()));
        try {
            // 总超时同时约束响应体读取；仅有连接超时无法限制缓慢的响应。
            long remaining = Math.max(1, deadline - System.nanoTime());
            return new Attempt(future.get(remaining, TimeUnit.NANOSECONDS), null, false);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new Attempt(null, new LocalizedIOException("ai.timeout"), false);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            // 只有连接阶段失败可以确定请求未送达服务端。
            boolean connect = e.getCause() instanceof ConnectException;
            return new Attempt(null, new LocalizedIOException("ai.network"), connect);
        }
    }

    // 按 Retry-After 或指数退避等待；剩余时间不足时放弃重试。
    private static boolean backoff(HttpResponse<?> response, int attempt, long deadline) throws InterruptedException {
        long wait = 500L << Math.min(attempt, 4);
        if (response != null && response.headers() != null) {
            var header = response.headers().firstValue("Retry-After");
            if (header.isPresent()) {
                try {
                    wait = Long.parseLong(header.get().strip()) * 1000;
                } catch (NumberFormatException ignored) {
                    // HTTP 日期格式不解析，沿用指数退避。
                }
            }
        }
        wait = Math.clamp(wait, 0, MAX_BACKOFF_MILLIS);
        if (System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(wait) + TimeUnit.SECONDS.toNanos(1) >= deadline) {
            return false;
        }
        Thread.sleep(wait);
        return true;
    }

    private static IOException statusError(int status) {
        String key = switch (status) {
            case 401, 403 -> "ai.auth_failed";
            case 404 -> "ai.not_found";
            case 429 -> "ai.rate_limited";
            default -> status >= 500 ? "ai.unavailable" : "ai.rejected";
        };
        return new LocalizedIOException(key, status);
    }

    /** 构造请求体，供测试检查线格式。 */
    public static String buildBody(Settings settings, AiRequest request) {
        return WireFormat.of(settings.provider()).body(settings, request);
    }

    /** 解析响应；任何结构错误都转换为不含原始响应内容的异常。 */
    public static AiResponse parseResponse(Provider provider, String json) throws IOException {
        try {
            return WireFormat.of(provider).parse(json);
        } catch (JsonParseException
                | IllegalStateException
                | NullPointerException
                | UnsupportedOperationException
                | ClassCastException e) {
            throw new LocalizedIOException("ai.malformed");
        }
    }

    public static AiResponse parseResponse(String provider, String json) throws IOException {
        return parseResponse(Provider.parse(provider), json);
    }

    @Override
    public void close() {
        client.shutdownNow();
    }

    /** 在缓冲超限响应体之前取消读取。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;

        LimitedBody(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer b : buffers) {
                if ((long) output.size() + b.remaining() > limit) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Response too large"));
                    return;
                }
                if (b.hasArray()) {
                    output.write(b.array(), b.arrayOffset() + b.position(), b.remaining());
                    b.position(b.limit());
                } else {
                    byte[] bytes = new byte[b.remaining()];
                    b.get(bytes);
                    output.writeBytes(bytes);
                }
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable t) {
            result.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            result.complete(output.toByteArray());
        }
    }
}
