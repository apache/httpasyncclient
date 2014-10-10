/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.http.impl.client.cache;

import java.io.IOException;
import java.net.URI;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpHost;
import org.apache.http.HttpMessage;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.ProtocolException;
import org.apache.http.ProtocolVersion;
import org.apache.http.RequestLine;
import org.apache.http.annotation.ThreadSafe;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.cache.CacheResponseStatus;
import org.apache.http.client.cache.HeaderConstants;
import org.apache.http.client.cache.HttpCacheContext;
import org.apache.http.client.cache.HttpCacheEntry;
import org.apache.http.client.cache.HttpCacheStorage;
import org.apache.http.client.cache.ResourceFactory;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.DateUtils;
import org.apache.http.concurrent.BasicFuture;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.nio.client.HttpAsyncClient;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.Args;
import org.apache.http.util.EntityUtils;
import org.apache.http.util.VersionInfo;

@ThreadSafe // So long as the responseCache implementation is threadsafe
public class CachingHttpAsyncClient implements HttpAsyncClient {

    private final static boolean SUPPORTS_RANGE_AND_CONTENT_RANGE_HEADERS = false;

    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong cacheUpdates = new AtomicLong();

    private final Map<ProtocolVersion, String> viaHeaders = new HashMap<ProtocolVersion, String>(4);

    private final HttpAsyncClient backend;
    private final HttpCache responseCache;
    private final CacheValidityPolicy validityPolicy;
    private final ResponseCachingPolicy responseCachingPolicy;
    private final CachedHttpResponseGenerator responseGenerator;
    private final CacheableRequestPolicy cacheableRequestPolicy;
    private final CachedResponseSuitabilityChecker suitabilityChecker;

    private final ConditionalRequestBuilder conditionalRequestBuilder;

    private final long maxObjectSizeBytes;
    private final boolean sharedCache;

    private final ResponseProtocolCompliance responseCompliance;
    private final RequestProtocolCompliance requestCompliance;

    private final AsynchronousAsyncValidator asynchAsyncRevalidator;

    private final Log log = LogFactory.getLog(getClass());

    CachingHttpAsyncClient(
            final HttpAsyncClient client,
            final HttpCache cache,
            final CacheConfig config) {
        super();
        Args.notNull(client, "HttpClient");
        Args.notNull(cache, "HttpCache");
        Args.notNull(config, "CacheConfig");
        this.maxObjectSizeBytes = config.getMaxObjectSize();
        this.sharedCache = config.isSharedCache();
        this.backend = client;
        this.responseCache = cache;
        this.validityPolicy = new CacheValidityPolicy();
        this.responseCachingPolicy = new ResponseCachingPolicy(this.maxObjectSizeBytes, this.sharedCache, false, config.is303CachingEnabled());
        this.responseGenerator = new CachedHttpResponseGenerator(this.validityPolicy);
        this.cacheableRequestPolicy = new CacheableRequestPolicy();
        this.suitabilityChecker = new CachedResponseSuitabilityChecker(this.validityPolicy, config);
        this.conditionalRequestBuilder = new ConditionalRequestBuilder();

        this.responseCompliance = new ResponseProtocolCompliance();
        this.requestCompliance = new RequestProtocolCompliance(config.isWeakETagOnPutDeleteAllowed());

        this.asynchAsyncRevalidator = makeAsynchronousValidator(config);
    }

    public CachingHttpAsyncClient() throws IOReactorException {
        this(HttpAsyncClients.createDefault(),
                new BasicHttpCache(),
                CacheConfig.DEFAULT);
    }

    public CachingHttpAsyncClient(final CacheConfig config) throws IOReactorException {
        this(HttpAsyncClients.createDefault(),
                new BasicHttpCache(config),
                config);
    }

    public CachingHttpAsyncClient(final HttpAsyncClient client) {
        this(client,
                new BasicHttpCache(),
                CacheConfig.DEFAULT);
    }

    public CachingHttpAsyncClient(final HttpAsyncClient client, final CacheConfig config) {
        this(client,
                new BasicHttpCache(config),
                config);
    }

    public CachingHttpAsyncClient(
            final HttpAsyncClient client,
            final ResourceFactory resourceFactory,
            final HttpCacheStorage storage,
            final CacheConfig config) {
        this(client,
                new BasicHttpCache(resourceFactory, storage, config),
                config);
    }

    public CachingHttpAsyncClient(
            final HttpAsyncClient client,
            final HttpCacheStorage storage,
            final CacheConfig config) {
        this(client,
                new BasicHttpCache(new HeapResourceFactory(), storage, config),
                config);
    }

    CachingHttpAsyncClient(
            final HttpAsyncClient backend,
            final CacheValidityPolicy validityPolicy,
            final ResponseCachingPolicy responseCachingPolicy,
            final HttpCache responseCache,
            final CachedHttpResponseGenerator responseGenerator,
            final CacheableRequestPolicy cacheableRequestPolicy,
            final CachedResponseSuitabilityChecker suitabilityChecker,
            final ConditionalRequestBuilder conditionalRequestBuilder,
            final ResponseProtocolCompliance responseCompliance,
            final RequestProtocolCompliance requestCompliance) {
        final CacheConfig config = CacheConfig.DEFAULT;
        this.maxObjectSizeBytes = config.getMaxObjectSize();
        this.sharedCache = config.isSharedCache();
        this.backend = backend;
        this.validityPolicy = validityPolicy;
        this.responseCachingPolicy = responseCachingPolicy;
        this.responseCache = responseCache;
        this.responseGenerator = responseGenerator;
        this.cacheableRequestPolicy = cacheableRequestPolicy;
        this.suitabilityChecker = suitabilityChecker;
        this.conditionalRequestBuilder = conditionalRequestBuilder;
        this.responseCompliance = responseCompliance;
        this.requestCompliance = requestCompliance;
        this.asynchAsyncRevalidator = makeAsynchronousValidator(config);
    }

    private AsynchronousAsyncValidator makeAsynchronousValidator(
            final CacheConfig config) {
        if (config.getAsynchronousWorkersMax() > 0) {
            return new AsynchronousAsyncValidator(this, config);
        }
        return null;
    }

    /**
     * Reports the number of times that the cache successfully responded
     * to an {@link HttpRequest} without contacting the origin server.
     * @return the number of cache hits
     */
    public long getCacheHits() {
        return this.cacheHits.get();
    }

    /**
     * Reports the number of times that the cache contacted the origin
     * server because it had no appropriate response cached.
     * @return the number of cache misses
     */
    public long getCacheMisses() {
        return this.cacheMisses.get();
    }

    /**
     * Reports the number of times that the cache was able to satisfy
     * a response by revalidating an existing but stale cache entry.
     * @return the number of cache revalidations
     */
    public long getCacheUpdates() {
        return this.cacheUpdates.get();
    }

    @Override
    public Future<HttpResponse> execute(
            final HttpHost target,
            final HttpRequest request,
            final FutureCallback<HttpResponse> callback) {
        return execute(target, request, null, callback);
    }

    @Override
    public <T> Future<T> execute(
            final HttpAsyncRequestProducer requestProducer,
            final HttpAsyncResponseConsumer<T> responseConsumer,
            final FutureCallback<T> callback) {
        return execute(requestProducer, responseConsumer, null, callback);
    }

    @Override
    public <T> Future<T> execute(
            final HttpAsyncRequestProducer requestProducer,
            final HttpAsyncResponseConsumer<T> responseConsumer,
            final HttpContext context,
            final FutureCallback<T> callback) {
        this.log.warn("CachingHttpAsyncClient does not support caching for streaming HTTP exchanges");
        return this.backend.execute(requestProducer, responseConsumer, context, callback);
    }

    @Override
    public Future<HttpResponse> execute(
            final HttpUriRequest request,
            final FutureCallback<HttpResponse> callback) {
        return execute(request, null, callback);
    }

    @Override
    public Future<HttpResponse> execute(
            final HttpUriRequest request,
            final HttpContext context,
            final FutureCallback<HttpResponse> callback) {
        final URI uri = request.getURI();
        final HttpHost httpHost = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());
        return execute(httpHost, request, context, callback);
    }

    @Override
    public Future<HttpResponse> execute(
            final HttpHost target,
            final HttpRequest originalRequest,
            final HttpContext context,
            final FutureCallback<HttpResponse> futureCallback) {
        final BasicFuture<HttpResponse> future = new BasicFuture<HttpResponse>(futureCallback);
        final HttpRequestWrapper request = HttpRequestWrapper.wrap(originalRequest);
        final HttpCacheContext clientContext = HttpCacheContext.adapt(context);
        // default response context
        setResponseStatus(clientContext, CacheResponseStatus.CACHE_MISS);

        final String via = generateViaHeader(request);

        if (clientRequestsOurOptions(request)) {
            setResponseStatus(clientContext, CacheResponseStatus.CACHE_MODULE_RESPONSE);
            future.completed(new OptionsHttp11Response());
            return future;
        }

        final HttpResponse fatalErrorResponse = getFatallyNoncompliantResponse(
                request, clientContext);
        if (fatalErrorResponse != null) {
            future.completed(fatalErrorResponse);
            return future;
        }

        try {
            this.requestCompliance.makeRequestCompliant(request);
        } catch (final ClientProtocolException e) {
            future.failed(e);
            return future;
        }
        request.addHeader(HeaderConstants.VIA,via);

        flushEntriesInvalidatedByRequest(target, request);

        if (!this.cacheableRequestPolicy.isServableFromCache(request)) {
            log.debug("Request is not servable from cache");
            callBackend(future, target, request, clientContext);
            return future;
        }

        final HttpCacheEntry entry = satisfyFromCache(target, request);
        if (entry == null) {
            log.debug("Cache miss");
            handleCacheMiss(future, target, request, clientContext);
        } else {
            try {
                handleCacheHit(future, target, request, clientContext, entry);
            } catch (final IOException e) {
                future.failed(e);
            }
        }
        return future;
    }

    private void handleCacheHit(
            final BasicFuture<HttpResponse> future,
            final HttpHost target,
            final HttpRequestWrapper request,
            final HttpCacheContext clientContext,
            final HttpCacheEntry entry) throws IOException {
        recordCacheHit(target, request);
        final HttpResponse out;
        final Date now = getCurrentDate();
        if (this.suitabilityChecker.canCachedResponseBeUsed(target, request, entry, now)) {
            log.debug("Cache hit");
            out = generateCachedResponse(request, clientContext, entry, now);
        } else if (!mayCallBackend(request)) {
            log.debug("Cache entry not suitable but only-if-cached requested");
            out = generateGatewayTimeout(clientContext);
        } else if (validityPolicy.isRevalidatable(entry)
                && !(entry.getStatusCode() == HttpStatus.SC_NOT_MODIFIED
                && !suitabilityChecker.isConditional(request))) {
            log.debug("Revalidating cache entry");
            revalidateCacheEntry(future, target, request, clientContext, entry, now);
            return;
        } else {
            log.debug("Cache entry not usable; calling backend");
            callBackend(future, target, request, clientContext);
            return;
        }
        clientContext.setAttribute(HttpClientContext.HTTP_ROUTE, new HttpRoute(target));
        clientContext.setAttribute(HttpClientContext.HTTP_TARGET_HOST, target);
        clientContext.setAttribute(HttpClientContext.HTTP_REQUEST, request);
        clientContext.setAttribute(HttpClientContext.HTTP_RESPONSE, out);
        clientContext.setAttribute(HttpClientContext.HTTP_REQ_SENT, Boolean.TRUE);
        future.completed(out);
    }

    private void revalidateCacheEntry(
            final BasicFuture<HttpResponse> future,
            final HttpHost target,
            final HttpRequestWrapper request,
            final HttpCacheContext clientContext,
            final HttpCacheEntry entry,
            final Date now) throws ClientProtocolException {

        try {
            if (this.asynchAsyncRevalidator != null
                && !staleResponseNotAllowed(request, entry, now)
                && this.validityPolicy.mayReturnStaleWhileRevalidating(entry, now)) {
                this.log.debug("Serving stale with asynchronous revalidation");
                final HttpResponse resp = this.responseGenerator.generateResponse(entry);
                resp.addHeader(HeaderConstants.WARNING, "110 localhost \"Response is stale\"");

                this.asynchAsyncRevalidator.revalidateCacheEntry(target, request, clientContext, entry);

                future.completed(resp);
                return;
            }

            final ChainedFutureCallback<HttpResponse> chainedFutureCallback = new ChainedFutureCallback<HttpResponse>(future) {

                @Override
                public void failed(final Exception ex) {
                    if(ex instanceof IOException) {
                        super.completed(handleRevalidationFailure(request, clientContext, entry, now));
                    } else {
                        super.failed(ex);
                    }
                }

            };

            final BasicFuture<HttpResponse> compositeFuture = new BasicFuture<HttpResponse>(chainedFutureCallback);
            revalidateCacheEntry(compositeFuture, target, request, clientContext, entry);
        } catch (final ProtocolException e) {
            throw new ClientProtocolException(e);
        }
    }

    private void handleCacheMiss(
            final BasicFuture<HttpResponse> future,
            final HttpHost target,
            final HttpRequestWrapper request,
            final HttpCacheContext clientContext) {
        recordCacheMiss(target, request);

        if (!mayCallBackend(request)) {
            future.completed(new BasicHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.SC_GATEWAY_TIMEOUT, "Gateway Timeout"));
            return;
        }

        final Map<String, Variant> variants = getExistingCacheVariants(target, request);
        if (variants != null && variants.size() > 0) {
            negotiateResponseFromVariants(future, target, request, clientContext, variants);
            return;
        }

        callBackend(future, target, request, clientContext);
    }

    private HttpCacheEntry satisfyFromCache(
            final HttpHost target,
            final HttpRequest request) {
        HttpCacheEntry entry = null;
        try {
            entry = this.responseCache.getCacheEntry(target, request);
        } catch (final IOException ioe) {
            this.log.warn("Unable to retrieve entries from cache", ioe);
        }
        return entry;
    }

    private HttpResponse getFatallyNoncompliantResponse(
            final HttpRequest request,
            final HttpCacheContext clientContext) {
        HttpResponse fatalErrorResponse = null;
        final List<RequestProtocolError> fatalError = this.requestCompliance.requestIsFatallyNonCompliant(request);

        for (final RequestProtocolError error : fatalError) {
            setResponseStatus(clientContext, CacheResponseStatus.CACHE_MODULE_RESPONSE);
            fatalErrorResponse = this.requestCompliance.getErrorForRequest(error);
        }
        return fatalErrorResponse;
    }

    private Map<String, Variant> getExistingCacheVariants(
            final HttpHost target,
            final HttpRequest request) {
        Map<String,Variant> variants = null;
        try {
            variants = this.responseCache.getVariantCacheEntriesWithEtags(target, request);
        } catch (final IOException ioe) {
            this.log.warn("Unable to retrieve variant entries from cache", ioe);
        }
        return variants;
    }

    private void recordCacheMiss(final HttpHost target, final HttpRequest request) {
        this.cacheMisses.getAndIncrement();
        if (this.log.isDebugEnabled()) {
            final RequestLine rl = request.getRequestLine();
            this.log.debug("Cache miss [host: " + target + "; uri: " + rl.getUri() + "]");
        }
    }

    private void recordCacheHit(final HttpHost target, final HttpRequest request) {
        this.cacheHits.getAndIncrement();
        if (this.log.isDebugEnabled()) {
            final RequestLine rl = request.getRequestLine();
            this.log.debug("Cache hit [host: " + target + "; uri: " + rl.getUri() + "]");
        }
    }

    private void recordCacheUpdate(final HttpCacheContext clientContext) {
        this.cacheUpdates.getAndIncrement();
        setResponseStatus(clientContext, CacheResponseStatus.VALIDATED);
    }

    private void flushEntriesInvalidatedByRequest(final HttpHost target,
            final HttpRequest request) {
        try {
            this.responseCache.flushInvalidatedCacheEntriesFor(target, request);
        } catch (final IOException ioe) {
            this.log.warn("Unable to flush invalidated entries from cache", ioe);
        }
    }

    private HttpResponse generateCachedResponse(
            final HttpRequest request,
            final HttpCacheContext clientContext,
            final HttpCacheEntry entry,
            final Date now) {
        final HttpResponse cachedResponse;
        if (request.containsHeader(HeaderConstants.IF_NONE_MATCH)
                || request.containsHeader(HeaderConstants.IF_MODIFIED_SINCE)) {
            cachedResponse = this.responseGenerator.generateNotModifiedResponse(entry);
        } else {
            cachedResponse = this.responseGenerator.generateResponse(entry);
        }
        setResponseStatus(clientContext, CacheResponseStatus.CACHE_HIT);
        if (this.validityPolicy.getStalenessSecs(entry, now) > 0L) {
            cachedResponse.addHeader("Warning","110 localhost \"Response is stale\"");
        }
        return cachedResponse;
    }

    private HttpResponse handleRevalidationFailure(
            final HttpRequest request,
            final HttpCacheContext clientContext,
            final HttpCacheEntry entry,
            final Date now) {
        if (staleResponseNotAllowed(request, entry, now)) {
            return generateGatewayTimeout(clientContext);
        }
        return unvalidatedCacheHit(clientContext, entry);
    }

    private HttpResponse generateGatewayTimeout(final HttpCacheContext clientContext) {
        setResponseStatus(clientContext, CacheResponseStatus.CACHE_MODULE_RESPONSE);
        return new BasicHttpResponse(HttpVersion.HTTP_1_1,
                HttpStatus.SC_GATEWAY_TIMEOUT, "Gateway Timeout");
    }

    private HttpResponse unvalidatedCacheHit(
            final HttpCacheContext clientContext,
            final HttpCacheEntry entry) {
        final HttpResponse cachedResponse = this.responseGenerator.generateResponse(entry);
        setResponseStatus(clientContext, CacheResponseStatus.CACHE_HIT);
        cachedResponse.addHeader(HeaderConstants.WARNING, "111 localhost \"Revalidation failed\"");
        return cachedResponse;
    }

    private boolean staleResponseNotAllowed(
            final HttpRequest request,
            final HttpCacheEntry entry,
            final Date now) {
        return this.validityPolicy.mustRevalidate(entry)
            || (isSharedCache() && this.validityPolicy.proxyRevalidate(entry))
            || explicitFreshnessRequest(request, entry, now);
    }

    private boolean mayCallBackend(final HttpRequest request) {
        for (final Header h: request.getHeaders(HeaderConstants.CACHE_CONTROL)) {
            for (final HeaderElement elt : h.getElements()) {
                if ("only-if-cached".equals(elt.getName())) {
                    this.log.debug("Request marked only-if-cached");
                    return false;
                }
            }
        }
        return true;
    }

    private boolean explicitFreshnessRequest(
            final HttpRequest request,
            final HttpCacheEntry entry,
            final Date now) {
        for(final Header h : request.getHeaders(HeaderConstants.CACHE_CONTROL)) {
            for(final HeaderElement elt : h.getElements()) {
                if (HeaderConstants.CACHE_CONTROL_MAX_STALE.equals(elt.getName())) {
                    try {
                        final int maxstale = Integer.parseInt(elt.getValue());
                        final long age = this.validityPolicy.getCurrentAgeSecs(entry, now);
                        final long lifetime = this.validityPolicy.getFreshnessLifetimeSecs(entry);
                        if (age - lifetime > maxstale) {
                            return true;
                        }
                    } catch (final NumberFormatException nfe) {
                        return true;
                    }
                } else if (HeaderConstants.CACHE_CONTROL_MIN_FRESH.equals(elt.getName())
                            || HeaderConstants.CACHE_CONTROL_MAX_AGE.equals(elt.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String generateViaHeader(final HttpMessage msg) {

        final ProtocolVersion pv = msg.getProtocolVersion();
        final String existingEntry = viaHeaders.get(pv);
        if (existingEntry != null) {
            return existingEntry;
        }

        final VersionInfo vi = VersionInfo.loadVersionInfo("org.apache.http.client", getClass().getClassLoader());
        final String release = (vi != null) ? vi.getRelease() : VersionInfo.UNAVAILABLE;

        final String value;
        if ("http".equalsIgnoreCase(pv.getProtocol())) {
            value = String.format("%d.%d localhost (Apache-HttpClient/%s (cache))", pv.getMajor(), pv.getMinor(),
                    release);
        } else {
            value = String.format("%s/%d.%d localhost (Apache-HttpClient/%s (cache))", pv.getProtocol(), pv.getMajor(),
                    pv.getMinor(), release);
        }
        viaHeaders.put(pv, value);

        return value;
    }

    private void setResponseStatus(final HttpCacheContext clientContext, final CacheResponseStatus value) {
        if (clientContext != null) {
            clientContext.setAttribute(HttpCacheContext.CACHE_RESPONSE_STATUS, value);
        }
    }

    /**
     * Reports whether this {@code CachingHttpClient} implementation
     * supports byte-range requests as specified by the {@code Range}
     * and {@code Content-Range} headers.
     * @return {@code true} if byte-range requests are supported
     */
    public boolean supportsRangeAndContentRangeHeaders() {
        return SUPPORTS_RANGE_AND_CONTENT_RANGE_HEADERS;
    }

    /**
     * Reports whether this {@code CachingHttpClient} is configured as
     * a shared (public) or non-shared (private) cache. See {@link
     * CacheConfig#setSharedCache(boolean)}.
     * @return {@code true} if we are behaving as a shared (public)
     *   cache
     */
    public boolean isSharedCache() {
        return this.sharedCache;
    }

    Date getCurrentDate() {
        return new Date();
    }

    boolean clientRequestsOurOptions(final HttpRequest request) {
        final RequestLine line = request.getRequestLine();

        if (!HeaderConstants.OPTIONS_METHOD.equals(line.getMethod())) {
            return false;
        }

        if (!"*".equals(line.getUri())) {
            return false;
        }
        return "0".equals(request.getFirstHeader(HeaderConstants.MAX_FORWARDS).getValue());
    }

    void callBackend(
            final BasicFuture<HttpResponse> future,
            final HttpHost target,
            final HttpRequestWrapper request,
            final HttpCacheContext clientContext) {
        final Date requestDate = getCurrentDate();
        this.log.trace("Calling the backend");

        final ChainedFutureCallback<HttpResponse> chainedFutureCallback = new ChainedFutureCallback<HttpResponse>(future) {

            @Override
            public void completed(final HttpResponse httpResponse) {
                httpResponse.addHeader(HeaderConstants.VIA, generateViaHeader(httpResponse));
                try {
                    final CloseableHttpResponse backendResponse = handleBackendResponse(
                            target, request, requestDate, getCurrentDate(),
                            Proxies.enhanceResponse(httpResponse));
                    super.completed(backendResponse);
                } catch (final IOException e) {
                    super.failed(e);
                }

            }

        };
        this.backend.execute(target, request, clientContext, chainedFutureCallback);
    }

    private boolean revalidationResponseIsTooOld(
            final HttpResponse backendResponse,
            final HttpCacheEntry cacheEntry) {
        final Header entryDateHeader = cacheEntry.getFirstHeader(HTTP.DATE_HEADER);
        final Header responseDateHeader = backendResponse.getFirstHeader(HTTP.DATE_HEADER);
        if (entryDateHeader != null && responseDateHeader != null) {
            final Date entryDate = DateUtils.parseDate(entryDateHeader.getValue());
            final Date respDate = DateUtils.parseDate(responseDateHeader.getValue());
            if (respDate != null && respDate.before(entryDate)) {
                return true;
            }
        }
        return false;
    }

    void negotiateResponseFromVariants(
            final BasicFuture<HttpResponse> future,
            final HttpHost target,
            final HttpRequestWrapper request,
            final HttpCacheContext clientContext,
            final Map<String, Variant> variants) {
        final HttpRequest conditionalRequest = this.conditionalRequestBuilder.buildConditionalRequestFromVariants(request, variants);

        final Date requestDate = getCurrentDate();

        final ChainedFutureCallback<HttpResponse> chainedFutureCallback = new ChainedFutureCallback<HttpResponse>(future) {

            @Override
            public void completed(final HttpResponse httpResponse) {
                final Date responseDate = getCurrentDate();

                httpResponse.addHeader(HeaderConstants.VIA, generateViaHeader(httpResponse));

                if (httpResponse.getStatusLine().getStatusCode() != HttpStatus.SC_NOT_MODIFIED) {
                    try {
                        future.completed(handleBackendResponse(
                                target, request, requestDate, responseDate,
                                Proxies.enhanceResponse(httpResponse)));
                        return;
                    } catch (final IOException e) {
                        future.failed(e);
                        return;
                    }
                }

                final Header resultEtagHeader = httpResponse.getFirstHeader(HeaderConstants.ETAG);
                if (resultEtagHeader == null) {
                    CachingHttpAsyncClient.this.log.warn("304 response did not contain ETag");
                    callBackend(future, target, request, clientContext);
                    return;
                }

                final String resultEtag = resultEtagHeader.getValue();
                final Variant matchingVariant = variants.get(resultEtag);
                if (matchingVariant == null) {
                    CachingHttpAsyncClient.this.log.debug("304 response did not contain ETag matching one sent in If-None-Match");
                    callBackend(future, target, request, clientContext);
                }

                final HttpCacheEntry matchedEntry = matchingVariant.getEntry();

                if (revalidationResponseIsTooOld(httpResponse, matchedEntry)) {
                    EntityUtils.consumeQuietly(httpResponse.getEntity());
                    retryRequestUnconditionally(future, target, request, clientContext, matchedEntry);
                    return;
                }

                recordCacheUpdate(clientContext);

                final HttpCacheEntry responseEntry = getUpdatedVariantEntry(target,
                        conditionalRequest, requestDate, responseDate, httpResponse,
                        matchingVariant, matchedEntry);

                final HttpResponse resp = CachingHttpAsyncClient.this.responseGenerator.generateResponse(responseEntry);
                tryToUpdateVariantMap(target, request, matchingVariant);

                if (shouldSendNotModifiedResponse(request, responseEntry)) {
                    future.completed(CachingHttpAsyncClient.this.responseGenerator.generateNotModifiedResponse(responseEntry));
                    return;
                }

                future.completed(resp);
            }

        };

        this.backend.execute(target, conditionalRequest, clientContext, chainedFutureCallback);
    }

    private void retryRequestUnconditionally(
            final BasicFuture<HttpResponse> future,
            final HttpHost target,
            final HttpRequestWrapper request,
            final HttpCacheContext clientContext,
            final HttpCacheEntry matchedEntry) {
        final HttpRequestWrapper unconditional = this.conditionalRequestBuilder
            .buildUnconditionalRequest(request, matchedEntry);
        callBackend(future, target, unconditional, clientContext);
    }

    private HttpCacheEntry getUpdatedVariantEntry(
            final HttpHost target,
            final HttpRequest conditionalRequest,
            final Date requestDate,
            final Date responseDate,
            final HttpResponse backendResponse,
            final Variant matchingVariant,
            final HttpCacheEntry matchedEntry) {
        HttpCacheEntry responseEntry = matchedEntry;
        try {
            responseEntry = this.responseCache.updateVariantCacheEntry(target, conditionalRequest,
                    matchedEntry, backendResponse, requestDate, responseDate, matchingVariant.getCacheKey());
        } catch (final IOException ioe) {
            this.log.warn("Could not update cache entry", ioe);
        }
        return responseEntry;
    }

    private void tryToUpdateVariantMap(
            final HttpHost target,
            final HttpRequest request,
            final Variant matchingVariant) {
        try {
            this.responseCache.reuseVariantEntryFor(target, request, matchingVariant);
        } catch (final IOException ioe) {
            this.log.warn("Could not update cache entry to reuse variant", ioe);
        }
    }

    private boolean shouldSendNotModifiedResponse(
            final HttpRequest request,
            final HttpCacheEntry responseEntry) {
        return (this.suitabilityChecker.isConditional(request)
                && this.suitabilityChecker.allConditionalsMatch(request, responseEntry, new Date()));
    }

    void revalidateCacheEntry(
            final BasicFuture<HttpResponse> future,
            final HttpHost target,
            final HttpRequestWrapper request,
            final HttpCacheContext clientContext,
            final HttpCacheEntry cacheEntry) throws ProtocolException {

        final HttpRequestWrapper conditionalRequest = this.conditionalRequestBuilder.buildConditionalRequest(request, cacheEntry);
        final Date requestDate = getCurrentDate();

        final ChainedFutureCallback<HttpResponse> chainedFutureCallback = new ChainedFutureCallback<HttpResponse>(future) {

            @Override
            public void completed(final HttpResponse httpResponse) {
                final Date responseDate = getCurrentDate();

                if (revalidationResponseIsTooOld(httpResponse, cacheEntry)) {
                    final HttpRequest unconditional = CachingHttpAsyncClient.this.conditionalRequestBuilder.buildUnconditionalRequest(request, cacheEntry);
                    final Date innerRequestDate = getCurrentDate();

                    final ChainedFutureCallback<HttpResponse> chainedFutureCallback2 = new ChainedFutureCallback<HttpResponse>(future) {

                        @Override
                        public void completed(final HttpResponse innerHttpResponse) {
                            final Date innerResponseDate = getCurrentDate();
                            revalidateCacheEntryCompleted(future,
                                    target, request, clientContext, cacheEntry,
                                    conditionalRequest, innerRequestDate,
                                    innerHttpResponse, innerResponseDate);
                        }

                    };
                    CachingHttpAsyncClient.this.backend.execute(
                            target, unconditional, clientContext, chainedFutureCallback2);
                }

                revalidateCacheEntryCompleted(future,
                        target, request, clientContext, cacheEntry,
                        conditionalRequest, requestDate,
                        httpResponse, responseDate);
            }

        };

        this.backend.execute(target, conditionalRequest, clientContext, chainedFutureCallback);
    }

    private void revalidateCacheEntryCompleted(
            final BasicFuture<HttpResponse> future,
            final HttpHost target,
            final HttpRequestWrapper request,
            final HttpCacheContext clientContext,
            final HttpCacheEntry cacheEntry,
            final HttpRequestWrapper conditionalRequest,
            final Date requestDate,
            final HttpResponse httpResponse,
            final Date responseDate) {

        httpResponse.addHeader(HeaderConstants.VIA, generateViaHeader(httpResponse));

        final int statusCode = httpResponse.getStatusLine().getStatusCode();
        if (statusCode == HttpStatus.SC_NOT_MODIFIED || statusCode == HttpStatus.SC_OK) {
            recordCacheUpdate(clientContext);
        }

        if (statusCode == HttpStatus.SC_NOT_MODIFIED) {
            final HttpCacheEntry updatedEntry;
            try {
                updatedEntry = CachingHttpAsyncClient.this.responseCache.updateCacheEntry(target, request, cacheEntry,
                        httpResponse, requestDate, responseDate);
            } catch (final IOException e) {
                future.failed(e);
                return;
            }
            if (CachingHttpAsyncClient.this.suitabilityChecker.isConditional(request)
                    && CachingHttpAsyncClient.this.suitabilityChecker.allConditionalsMatch(request, updatedEntry, new Date())) {
                future.completed(CachingHttpAsyncClient.this.responseGenerator.generateNotModifiedResponse(updatedEntry));
                return;
            }
            future.completed(CachingHttpAsyncClient.this.responseGenerator.generateResponse(updatedEntry));
            return;
        }

        if (staleIfErrorAppliesTo(statusCode)
            && !staleResponseNotAllowed(request, cacheEntry, getCurrentDate())
            && CachingHttpAsyncClient.this.validityPolicy.mayReturnStaleIfError(request, cacheEntry, responseDate)) {
            final HttpResponse cachedResponse = CachingHttpAsyncClient.this.responseGenerator.generateResponse(cacheEntry);
            cachedResponse.addHeader(HeaderConstants.WARNING, "110 localhost \"Response is stale\"");
            future.completed(cachedResponse);
            return;
        }

        try {
            final CloseableHttpResponse backendResponse = handleBackendResponse(
                    target, conditionalRequest, requestDate, responseDate,
                    Proxies.enhanceResponse(httpResponse));
            future.completed(backendResponse);
        } catch (final IOException e) {
            future.failed(e);
        }
    }

    private boolean staleIfErrorAppliesTo(final int statusCode) {
        return statusCode == HttpStatus.SC_INTERNAL_SERVER_ERROR
                || statusCode == HttpStatus.SC_BAD_GATEWAY
                || statusCode == HttpStatus.SC_SERVICE_UNAVAILABLE
                || statusCode == HttpStatus.SC_GATEWAY_TIMEOUT;
    }

    CloseableHttpResponse handleBackendResponse(
            final HttpHost target,
            final HttpRequestWrapper request,
            final Date requestDate,
            final Date responseDate,
            final CloseableHttpResponse backendResponse) throws IOException {

        this.log.debug("Handling Backend response");
        this.responseCompliance.ensureProtocolCompliance(request, backendResponse);

        final boolean cacheable = this.responseCachingPolicy.isResponseCacheable(request, backendResponse);
        this.responseCache.flushInvalidatedCacheEntriesFor(target, request, backendResponse);
        if (cacheable &&
            !alreadyHaveNewerCacheEntry(target, request, backendResponse)) {
            storeRequestIfModifiedSinceFor304Response(request, backendResponse);
            return this.responseCache.cacheAndReturnResponse(target, request, backendResponse, requestDate,
                    responseDate);
        }
        if (!cacheable) {
            try {
                this.responseCache.flushCacheEntriesFor(target, request);
            } catch (final IOException ioe) {
                this.log.warn("Unable to flush invalid cache entries", ioe);
            }
        }
        return backendResponse;
    }

    /**
     * For 304 Not modified responses, adds a "Last-Modified" header with the
     * value of the "If-Modified-Since" header passed in the request. This
     * header is required to be able to reuse match the cache entry for
     * subsequent requests but as defined in http specifications it is not
     * included in 304 responses by backend servers. This header will not be
     * included in the resulting response.
     */
    private void storeRequestIfModifiedSinceFor304Response(
            final HttpRequest request,
            final HttpResponse backendResponse) {
        if (backendResponse.getStatusLine().getStatusCode() == HttpStatus.SC_NOT_MODIFIED) {
            final Header h = request.getFirstHeader("If-Modified-Since");
            if (h != null) {
                backendResponse.addHeader("Last-Modified", h.getValue());
            }
        }
    }

    private boolean alreadyHaveNewerCacheEntry(
            final HttpHost target,
            final HttpRequest request,
            final HttpResponse backendResponse) {
        HttpCacheEntry existing = null;
        try {
            existing = this.responseCache.getCacheEntry(target, request);
        } catch (final IOException ioe) {
            // nop
        }
        if (existing == null) {
            return false;
        }
        final Header entryDateHeader = existing.getFirstHeader(HTTP.DATE_HEADER);
        if (entryDateHeader == null) {
            return false;
        }
        final Header responseDateHeader = backendResponse.getFirstHeader(HTTP.DATE_HEADER);
        if (responseDateHeader == null) {
            return false;
        }
        final Date entryDate = DateUtils.parseDate(entryDateHeader.getValue());
        final Date responseDate = DateUtils.parseDate(responseDateHeader.getValue());
        return responseDate != null && responseDate.before(entryDate);
    }

}
