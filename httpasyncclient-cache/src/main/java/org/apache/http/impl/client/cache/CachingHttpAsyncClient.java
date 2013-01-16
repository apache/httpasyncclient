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
import org.apache.http.client.cache.HttpCacheEntry;
import org.apache.http.client.cache.HttpCacheStorage;
import org.apache.http.client.cache.ResourceFactory;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.concurrent.BasicFuture;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.cookie.DateParseException;
import org.apache.http.impl.cookie.DateUtils;
import org.apache.http.impl.nio.client.DefaultHttpAsyncClient;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.nio.client.HttpAsyncClient;
import org.apache.http.nio.conn.ClientAsyncConnectionManager;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.nio.reactor.IOReactorStatus;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.VersionInfo;

@ThreadSafe // So long as the responseCache implementation is threadsafe
public class CachingHttpAsyncClient implements HttpAsyncClient {

    public static final String CACHE_RESPONSE_STATUS = "http.cache.response.status";

    private final static boolean SUPPORTS_RANGE_AND_CONTENT_RANGE_HEADERS = false;

    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong cacheUpdates = new AtomicLong();

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
        if (client == null) {
            throw new IllegalArgumentException("HttpClient may not be null");
        }
        if (cache == null) {
            throw new IllegalArgumentException("HttpCache may not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("CacheConfig may not be null");
        }
        this.maxObjectSizeBytes = config.getMaxObjectSize();
        this.sharedCache = config.isSharedCache();
        this.backend = client;
        this.responseCache = cache;
        this.validityPolicy = new CacheValidityPolicy();
        this.responseCachingPolicy = new ResponseCachingPolicy(this.maxObjectSizeBytes, this.sharedCache);
        this.responseGenerator = new CachedHttpResponseGenerator(this.validityPolicy);
        this.cacheableRequestPolicy = new CacheableRequestPolicy();
        this.suitabilityChecker = new CachedResponseSuitabilityChecker(this.validityPolicy, config);
        this.conditionalRequestBuilder = new ConditionalRequestBuilder();

        this.responseCompliance = new ResponseProtocolCompliance();
        this.requestCompliance = new RequestProtocolCompliance();

        this.asynchAsyncRevalidator = makeAsynchronousValidator(config);
    }

    public CachingHttpAsyncClient() throws IOReactorException {
        this(new DefaultHttpAsyncClient(),
                new BasicHttpCache(),
                new CacheConfig());
    }

    public CachingHttpAsyncClient(final CacheConfig config) throws IOReactorException {
        this(new DefaultHttpAsyncClient(),
                new BasicHttpCache(config),
                config);
    }

    public CachingHttpAsyncClient(final HttpAsyncClient client) {
        this(client,
                new BasicHttpCache(),
                new CacheConfig());
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
        final CacheConfig config = new CacheConfig();
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

    public Future<HttpResponse> execute(
            final HttpHost target,
            final HttpRequest request,
            final FutureCallback<HttpResponse> callback) {
        return execute(target, request, null, callback);
    }

    public <T> Future<T> execute(
            final HttpAsyncRequestProducer requestProducer,
            final HttpAsyncResponseConsumer<T> responseConsumer,
            final FutureCallback<T> callback) {
        return execute(requestProducer, responseConsumer, null, callback);
    }

    public <T> Future<T> execute(
            final HttpAsyncRequestProducer requestProducer,
            final HttpAsyncResponseConsumer<T> responseConsumer,
            final HttpContext context,
            final FutureCallback<T> callback) {
        this.log.warn("CachingHttpAsyncClient does not caching for streaming HTTP exchanges");
        return this.backend.execute(requestProducer, responseConsumer, context, callback);
    }

    public Future<HttpResponse> execute(
            final HttpUriRequest request,
            final FutureCallback<HttpResponse> callback) {
        return execute(request, null, callback);
    }

    public Future<HttpResponse> execute(
            final HttpUriRequest request,
            final HttpContext context,
            final FutureCallback<HttpResponse> callback) {
        final URI uri = request.getURI();
        final HttpHost httpHost = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());
        return execute(httpHost, request, context, callback);
    }

    public ClientAsyncConnectionManager getConnectionManager() {
        return this.backend.getConnectionManager();
    }

    public HttpParams getParams() {
        return this.backend.getParams();
    }

    public Future<HttpResponse> execute(
            final HttpHost target,
            final HttpRequest request,
            final HttpContext context,
            final FutureCallback<HttpResponse> futureCallback) {
        // default response context
        setResponseStatus(context, CacheResponseStatus.CACHE_MISS);

        final String via = generateViaHeader(request);

        if (clientRequestsOurOptions(request)) {
            setResponseStatus(context, CacheResponseStatus.CACHE_MODULE_RESPONSE);
            final BasicFuture<HttpResponse> future = new BasicFuture<HttpResponse>(futureCallback);
            future.completed(new OptionsHttp11Response());
            return future;
        }

        final HttpResponse fatalErrorResponse = getFatallyNoncompliantResponse(
                request, context);
        if (fatalErrorResponse != null) {
            final BasicFuture<HttpResponse> future = new BasicFuture<HttpResponse>(futureCallback);
            future.completed(fatalErrorResponse);
            return future;
        }

        HttpRequest httRequest = request;
        try {
            httRequest = this.requestCompliance.makeRequestCompliant(request);
        } catch (final ClientProtocolException e) {
            final BasicFuture<HttpResponse> future = new BasicFuture<HttpResponse>(futureCallback);
            future.failed(e);
            return future;
        }
        httRequest.addHeader("Via",via);

        flushEntriesInvalidatedByRequest(target, httRequest);

        if (!this.cacheableRequestPolicy.isServableFromCache(httRequest)) {
            return callBackend(target, httRequest, context, futureCallback);
        }

        final HttpCacheEntry entry = satisfyFromCache(target, httRequest);
        if (entry == null) {
            return handleCacheMiss(target, httRequest, context, futureCallback);
        }

        try {
            return handleCacheHit(target, httRequest, context, entry, futureCallback);
        } catch (final ClientProtocolException e) {
            final BasicFuture<HttpResponse> future = new BasicFuture<HttpResponse>(futureCallback);
            future.failed(e);
            return future;
        } catch (final IOException e) {
            final BasicFuture<HttpResponse> future = new BasicFuture<HttpResponse>(futureCallback);
            future.failed(e);
            return future;
        }
    }

    private Future<HttpResponse> handleCacheHit(final HttpHost target, final HttpRequest request,
            final HttpContext context, final HttpCacheEntry entry,
            final FutureCallback<HttpResponse> futureCallback)
            throws ClientProtocolException, IOException {
        recordCacheHit(target, request);

        final Date now = getCurrentDate();
        if (this.suitabilityChecker.canCachedResponseBeUsed(target, request, entry, now)) {
            final BasicFuture<HttpResponse> future = new BasicFuture<HttpResponse>(futureCallback);
            future.completed(generateCachedResponse(request, context, entry, now));
            return future;
        }

        if (!mayCallBackend(request)) {
            final BasicFuture<HttpResponse> future = new BasicFuture<HttpResponse>(futureCallback);
            future.completed(generateGatewayTimeout(context));
            return future;
        }

        if (this.validityPolicy.isRevalidatable(entry)) {
            return revalidateCacheEntry(target, request, context, entry, now, futureCallback);
        }
        return callBackend(target, request, context, futureCallback);
    }

    private Future<HttpResponse> revalidateCacheEntry(final HttpHost target,
            final HttpRequest request, final HttpContext context, final HttpCacheEntry entry,
            final Date now, final FutureCallback<HttpResponse> futureCallback) throws ClientProtocolException {
        this.log.debug("Revalidating the cache entry");

        try {
            if (this.asynchAsyncRevalidator != null
                && !staleResponseNotAllowed(request, entry, now)
                && this.validityPolicy.mayReturnStaleWhileRevalidating(entry, now)) {
                final HttpResponse resp = this.responseGenerator.generateResponse(entry);
                resp.addHeader(HeaderConstants.WARNING, "110 localhost \"Response is stale\"");

                this.asynchAsyncRevalidator.revalidateCacheEntry(target, request, context, entry);

                final BasicFuture<HttpResponse> future = new BasicFuture<HttpResponse>(futureCallback);
                future.completed(resp);
                return future;
            }
            return revalidateCacheEntry(target, request, context, entry, new FutureCallback<HttpResponse> () {

                public void cancelled() {
                    futureCallback.cancelled();
                }

                public void completed(final HttpResponse httpResponse) {
                    futureCallback.completed(httpResponse);
                }

                public void failed(final Exception e) {
                    if(e instanceof IOException) {
                        futureCallback.completed(handleRevalidationFailure(request, context, entry, now));
                        return;
                    }
                    futureCallback.failed(e);
                }
            });
        } catch (final ProtocolException e) {
            throw new ClientProtocolException(e);
        }
    }

    private Future<HttpResponse> handleCacheMiss(final HttpHost target, final HttpRequest request,
            final HttpContext context, final FutureCallback<HttpResponse> futureCallback) {
        recordCacheMiss(target, request);

        if (!mayCallBackend(request)) {
            final BasicFuture<HttpResponse> future = new BasicFuture<HttpResponse>(futureCallback);
            future.completed(new BasicHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.SC_GATEWAY_TIMEOUT, "Gateway Timeout"));
            return future;
        }

        final Map<String, Variant> variants =
            getExistingCacheVariants(target, request);
        if (variants != null && variants.size() > 0) {
            return negotiateResponseFromVariants(target, request, context, variants, futureCallback);
        }

        return callBackend(target, request, context, futureCallback);
    }

    private HttpCacheEntry satisfyFromCache(final HttpHost target, final HttpRequest request) {
        HttpCacheEntry entry = null;
        try {
            entry = this.responseCache.getCacheEntry(target, request);
        } catch (final IOException ioe) {
            this.log.warn("Unable to retrieve entries from cache", ioe);
        }
        return entry;
    }

    private HttpResponse getFatallyNoncompliantResponse(final HttpRequest request,
            final HttpContext context) {
        HttpResponse fatalErrorResponse = null;
        final List<RequestProtocolError> fatalError = this.requestCompliance.requestIsFatallyNonCompliant(request);

        for (final RequestProtocolError error : fatalError) {
            setResponseStatus(context, CacheResponseStatus.CACHE_MODULE_RESPONSE);
            fatalErrorResponse = this.requestCompliance.getErrorForRequest(error);
        }
        return fatalErrorResponse;
    }

    private Map<String, Variant> getExistingCacheVariants(final HttpHost target,
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

    private void recordCacheUpdate(final HttpContext context) {
        this.cacheUpdates.getAndIncrement();
        setResponseStatus(context, CacheResponseStatus.VALIDATED);
    }

    private void flushEntriesInvalidatedByRequest(final HttpHost target,
            final HttpRequest request) {
        try {
            this.responseCache.flushInvalidatedCacheEntriesFor(target, request);
        } catch (final IOException ioe) {
            this.log.warn("Unable to flush invalidated entries from cache", ioe);
        }
    }

    private HttpResponse generateCachedResponse(final HttpRequest request,
            final HttpContext context, final HttpCacheEntry entry, final Date now) {
        final HttpResponse cachedResponse;
        if (request.containsHeader(HeaderConstants.IF_NONE_MATCH)
                || request.containsHeader(HeaderConstants.IF_MODIFIED_SINCE)) {
            cachedResponse = this.responseGenerator.generateNotModifiedResponse(entry);
        } else {
            cachedResponse = this.responseGenerator.generateResponse(entry);
        }
        setResponseStatus(context, CacheResponseStatus.CACHE_HIT);
        if (this.validityPolicy.getStalenessSecs(entry, now) > 0L) {
            cachedResponse.addHeader("Warning","110 localhost \"Response is stale\"");
        }
        return cachedResponse;
    }

    private HttpResponse handleRevalidationFailure(final HttpRequest request,
            final HttpContext context, final HttpCacheEntry entry, final Date now) {
        if (staleResponseNotAllowed(request, entry, now)) {
            return generateGatewayTimeout(context);
        }
        return unvalidatedCacheHit(context, entry);
    }

    private HttpResponse generateGatewayTimeout(final HttpContext context) {
        setResponseStatus(context, CacheResponseStatus.CACHE_MODULE_RESPONSE);
        return new BasicHttpResponse(HttpVersion.HTTP_1_1,
                HttpStatus.SC_GATEWAY_TIMEOUT, "Gateway Timeout");
    }

    private HttpResponse unvalidatedCacheHit(final HttpContext context,
            final HttpCacheEntry entry) {
        final HttpResponse cachedResponse = this.responseGenerator.generateResponse(entry);
        setResponseStatus(context, CacheResponseStatus.CACHE_HIT);
        cachedResponse.addHeader(HeaderConstants.WARNING, "111 localhost \"Revalidation failed\"");
        return cachedResponse;
    }

    private boolean staleResponseNotAllowed(final HttpRequest request,
            final HttpCacheEntry entry, final Date now) {
        return this.validityPolicy.mustRevalidate(entry)
            || (isSharedCache() && this.validityPolicy.proxyRevalidate(entry))
            || explicitFreshnessRequest(request, entry, now);
    }

    private boolean mayCallBackend(final HttpRequest request) {
        for (final Header h: request.getHeaders("Cache-Control")) {
            for (final HeaderElement elt : h.getElements()) {
                if ("only-if-cached".equals(elt.getName())) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean explicitFreshnessRequest(final HttpRequest request, final HttpCacheEntry entry, final Date now) {
        for(final Header h : request.getHeaders("Cache-Control")) {
            for(final HeaderElement elt : h.getElements()) {
                if ("max-stale".equals(elt.getName())) {
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
                } else if ("min-fresh".equals(elt.getName())
                            || "max-age".equals(elt.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String generateViaHeader(final HttpMessage msg) {
        final VersionInfo vi = VersionInfo.loadVersionInfo("org.apache.http.client", getClass().getClassLoader());
        final String release = (vi != null) ? vi.getRelease() : VersionInfo.UNAVAILABLE;
        final ProtocolVersion pv = msg.getProtocolVersion();
        if ("http".equalsIgnoreCase(pv.getProtocol())) {
            return String.format("%d.%d localhost (Apache-HttpClient/%s (cache))",
                new Integer(pv.getMajor()), new Integer(pv.getMinor()), release);
        }
        return String.format("%s/%d.%d localhost (Apache-HttpClient/%s (cache))",
                pv.getProtocol(), new Integer(pv.getMajor()), new Integer(pv.getMinor()), release);
    }

    private void setResponseStatus(final HttpContext context, final CacheResponseStatus value) {
        if (context != null) {
            context.setAttribute(CACHE_RESPONSE_STATUS, value);
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

        if (!"0".equals(request.getFirstHeader(HeaderConstants.MAX_FORWARDS).getValue())) {
            return false;
        }

        return true;
    }

    Future<HttpResponse> callBackend(final HttpHost target, final HttpRequest request, final HttpContext context, final FutureCallback<HttpResponse> futureCallback) {
        final Date requestDate = getCurrentDate();
        this.log.debug("Calling the backend");
        return this.backend.execute(target, request, context, new FutureCallback<HttpResponse>() {

            public void cancelled() {
                futureCallback.cancelled();
            }

            public void completed(final HttpResponse httpResponse) {
                httpResponse.addHeader("Via", generateViaHeader(httpResponse));
                try {
                    final HttpResponse backendResponse = handleBackendResponse(target, request, requestDate, getCurrentDate(), httpResponse);
                    futureCallback.completed(backendResponse);
                } catch (final IOException e) {
                    futureCallback.failed(e);
                    return;
                }

            }

            public void failed(final Exception e) {
                futureCallback.failed(e);
            }

        });
    }

    private boolean revalidationResponseIsTooOld(final HttpResponse backendResponse,
            final HttpCacheEntry cacheEntry) {
        final Header entryDateHeader = cacheEntry.getFirstHeader("Date");
        final Header responseDateHeader = backendResponse.getFirstHeader("Date");
        if (entryDateHeader != null && responseDateHeader != null) {
            try {
                final Date entryDate = DateUtils.parseDate(entryDateHeader.getValue());
                final Date respDate = DateUtils.parseDate(responseDateHeader.getValue());
                if (respDate.before(entryDate)) {
                    return true;
                }
            } catch (final DateParseException e) {
                // either backend response or cached entry did not have a valid
                // Date header, so we can't tell if they are out of order
                // according to the origin clock; thus we can skip the
                // unconditional retry recommended in 13.2.6 of RFC 2616.
            }
        }
        return false;
    }

    Future<HttpResponse> negotiateResponseFromVariants(final HttpHost target,
            final HttpRequest request, final HttpContext context,
            final Map<String, Variant> variants,
            final FutureCallback<HttpResponse> futureCallback) {
        final HttpRequest conditionalRequest = this.conditionalRequestBuilder.buildConditionalRequestFromVariants(request, variants);

        final Date requestDate = getCurrentDate();
        //HttpResponse backendResponse =
        return this.backend.execute(target, conditionalRequest, new FutureCallback<HttpResponse> () {

            public void cancelled() {
                futureCallback.cancelled();
            }

            public void completed(final HttpResponse httpResponse) {
                final Date responseDate = getCurrentDate();

                httpResponse.addHeader("Via", generateViaHeader(httpResponse));

                if (httpResponse.getStatusLine().getStatusCode() != HttpStatus.SC_NOT_MODIFIED) {
                    try {
                        final HttpResponse backendResponse = handleBackendResponse(target, request, requestDate, responseDate, httpResponse);
                        futureCallback.completed(backendResponse);
                        return;
                    } catch (final IOException e) {
                        futureCallback.failed(e);
                        return;
                    }
                }

                final Header resultEtagHeader = httpResponse.getFirstHeader(HeaderConstants.ETAG);
                if (resultEtagHeader == null) {
                    CachingHttpAsyncClient.this.log.warn("304 response did not contain ETag");
                    callBackend(target, request, context, new FutureCallback<HttpResponse>() {

                        public void cancelled() {
                            futureCallback.cancelled();
                        }

                        public void completed(final HttpResponse innerHttpResponse) {
                            futureCallback.completed(innerHttpResponse);
                        }

                        public void failed(final Exception e) {
                            futureCallback.failed(e);
                        }

                    });
                    return;
                }

                final String resultEtag = resultEtagHeader.getValue();
                final Variant matchingVariant = variants.get(resultEtag);
                if (matchingVariant == null) {
                    CachingHttpAsyncClient.this.log.debug("304 response did not contain ETag matching one sent in If-None-Match");
                    callBackend(target, request, context, new FutureCallback<HttpResponse>() {

                        public void cancelled() {
                            futureCallback.cancelled();
                        }

                        public void completed(final HttpResponse innerHttpResponse) {
                            futureCallback.completed(innerHttpResponse);
                        }

                        public void failed(final Exception e) {
                            futureCallback.failed(e);
                        }

                    });
                    return;
                }

                final HttpCacheEntry matchedEntry = matchingVariant.getEntry();

                if (revalidationResponseIsTooOld(httpResponse, matchedEntry)) {
                    retryRequestUnconditionally(target, request, context, matchedEntry, futureCallback);
                    return;
                }

                recordCacheUpdate(context);

                final HttpCacheEntry responseEntry = getUpdatedVariantEntry(target,
                        conditionalRequest, requestDate, responseDate, httpResponse,
                        matchingVariant, matchedEntry);

                final HttpResponse resp = CachingHttpAsyncClient.this.responseGenerator.generateResponse(responseEntry);
                tryToUpdateVariantMap(target, request, matchingVariant);

                if (shouldSendNotModifiedResponse(request, responseEntry)) {
                    final HttpResponse backendResponse = CachingHttpAsyncClient.this.responseGenerator.generateNotModifiedResponse(responseEntry);
                    futureCallback.completed(backendResponse);
                    return;
                }

                final HttpResponse backendResponse = resp;
                futureCallback.completed(backendResponse);
                return;
            }

            public void failed(final Exception e) {
                futureCallback.failed(e);
            }
        });
    }

    private void retryRequestUnconditionally(final HttpHost target,
            final HttpRequest request, final HttpContext context,
            final HttpCacheEntry matchedEntry, final FutureCallback<HttpResponse> futureCallback) {
        final HttpRequest unconditional = this.conditionalRequestBuilder
            .buildUnconditionalRequest(request, matchedEntry);
        callBackend(target, unconditional, context, futureCallback);
    }

    private HttpCacheEntry getUpdatedVariantEntry(final HttpHost target,
            final HttpRequest conditionalRequest, final Date requestDate,
            final Date responseDate, final HttpResponse backendResponse,
            final Variant matchingVariant, final HttpCacheEntry matchedEntry) {
        HttpCacheEntry responseEntry = matchedEntry;
        try {
            responseEntry = this.responseCache.updateVariantCacheEntry(target, conditionalRequest,
                    matchedEntry, backendResponse, requestDate, responseDate, matchingVariant.getCacheKey());
        } catch (final IOException ioe) {
            this.log.warn("Could not update cache entry", ioe);
        }
        return responseEntry;
    }

    private void tryToUpdateVariantMap(final HttpHost target, final HttpRequest request,
            final Variant matchingVariant) {
        try {
            this.responseCache.reuseVariantEntryFor(target, request, matchingVariant);
        } catch (final IOException ioe) {
            this.log.warn("Could not update cache entry to reuse variant", ioe);
        }
    }

    private boolean shouldSendNotModifiedResponse(final HttpRequest request,
            final HttpCacheEntry responseEntry) {
        return (this.suitabilityChecker.isConditional(request)
                && this.suitabilityChecker.allConditionalsMatch(request, responseEntry, new Date()));
    }

    Future<HttpResponse> revalidateCacheEntry(
            final HttpHost target,
            final HttpRequest request,
            final HttpContext context,
            final HttpCacheEntry cacheEntry,
            final FutureCallback<HttpResponse> futureCallback) throws ProtocolException {

        final HttpRequest conditionalRequest = this.conditionalRequestBuilder.buildConditionalRequest(request, cacheEntry);
        final Date requestDate = getCurrentDate();
        return this.backend.execute(target, conditionalRequest, context, new FutureCallback<HttpResponse>() {

            public void cancelled() {
                futureCallback.cancelled();
            }

            public void completed(final HttpResponse httpResponse) {
                   final Date responseDate = getCurrentDate();

                    if (revalidationResponseIsTooOld(httpResponse, cacheEntry)) {
                        final HttpRequest unconditional = CachingHttpAsyncClient.this.conditionalRequestBuilder.buildUnconditionalRequest(request, cacheEntry);
                        final Date innerRequestDate = getCurrentDate();
                        CachingHttpAsyncClient.this.backend.execute(target, unconditional, context, new FutureCallback<HttpResponse>() {

                            public void cancelled() {
                                futureCallback.cancelled();
                            }

                            public void completed(final HttpResponse innerHttpResponse) {
                                final Date innerResponseDate = getCurrentDate();
                                revalidateCacheEntryCompleted(target, request,
                                        context, cacheEntry, futureCallback,
                                        conditionalRequest, innerRequestDate,
                                        innerHttpResponse, innerResponseDate);
                            }

                            public void failed(final Exception e) {
                                futureCallback.failed(e);
                            }

                        });
                        return;
                    }
                    revalidateCacheEntryCompleted(target, request,
                            context, cacheEntry, futureCallback,
                            conditionalRequest, requestDate,
                            httpResponse, responseDate);
            }

            public void failed(final Exception e) {
                futureCallback.failed(e);
            }

        });
    }

    private void revalidateCacheEntryCompleted(
            final HttpHost target,
            final HttpRequest request,
            final HttpContext context,
            final HttpCacheEntry cacheEntry,
            final FutureCallback<HttpResponse> futureCallback,
            final HttpRequest conditionalRequest,
            final Date requestDate,
            final HttpResponse httpResponse,
            final Date responseDate) {

        httpResponse.addHeader("Via", generateViaHeader(httpResponse));

        final int statusCode = httpResponse.getStatusLine().getStatusCode();
        if (statusCode == HttpStatus.SC_NOT_MODIFIED || statusCode == HttpStatus.SC_OK) {
            recordCacheUpdate(context);
        }

        if (statusCode == HttpStatus.SC_NOT_MODIFIED) {
            HttpCacheEntry updatedEntry = null;
            try {
                updatedEntry = CachingHttpAsyncClient.this.responseCache.updateCacheEntry(target, request, cacheEntry,
                        httpResponse, requestDate, responseDate);
            } catch (final IOException e) {
                futureCallback.failed(e);
                return;
            }
            if (CachingHttpAsyncClient.this.suitabilityChecker.isConditional(request)
                    && CachingHttpAsyncClient.this.suitabilityChecker.allConditionalsMatch(request, updatedEntry, new Date())) {
                futureCallback.completed(CachingHttpAsyncClient.this.responseGenerator.generateNotModifiedResponse(updatedEntry));
                return;
            }
            futureCallback.completed(CachingHttpAsyncClient.this.responseGenerator.generateResponse(updatedEntry));
            return;
        }

        if (staleIfErrorAppliesTo(statusCode)
            && !staleResponseNotAllowed(request, cacheEntry, getCurrentDate())
            && CachingHttpAsyncClient.this.validityPolicy.mayReturnStaleIfError(request, cacheEntry, responseDate)) {
            final HttpResponse cachedResponse = CachingHttpAsyncClient.this.responseGenerator.generateResponse(cacheEntry);
            cachedResponse.addHeader(HeaderConstants.WARNING, "110 localhost \"Response is stale\"");
            futureCallback.completed(cachedResponse);
            return;
        }

        try {
            final HttpResponse backendResponse = handleBackendResponse(target, conditionalRequest,
                    requestDate, responseDate, httpResponse);
                futureCallback.completed(backendResponse);
        } catch (final IOException e) {
            futureCallback.failed(e);
            return;
        }
    }

    private boolean staleIfErrorAppliesTo(final int statusCode) {
        return statusCode == HttpStatus.SC_INTERNAL_SERVER_ERROR
                || statusCode == HttpStatus.SC_BAD_GATEWAY
                || statusCode == HttpStatus.SC_SERVICE_UNAVAILABLE
                || statusCode == HttpStatus.SC_GATEWAY_TIMEOUT;
    }

    HttpResponse handleBackendResponse(
            final HttpHost target,
            final HttpRequest request,
            final Date requestDate,
            final Date responseDate,
            final HttpResponse backendResponse) throws IOException {

        this.log.debug("Handling Backend response");
        this.responseCompliance.ensureProtocolCompliance(request, backendResponse);

        final boolean cacheable = this.responseCachingPolicy.isResponseCacheable(request, backendResponse);
        this.responseCache.flushInvalidatedCacheEntriesFor(target, request, backendResponse);
        if (cacheable &&
            !alreadyHaveNewerCacheEntry(target, request, backendResponse)) {
            try {
                return this.responseCache.cacheAndReturnResponse(target, request, backendResponse, requestDate,
                        responseDate);
            } catch (final IOException ioe) {
                this.log.warn("Unable to store entries in cache", ioe);
            }
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

    private boolean alreadyHaveNewerCacheEntry(final HttpHost target, final HttpRequest request,
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
        final Header entryDateHeader = existing.getFirstHeader("Date");
        if (entryDateHeader == null) {
            return false;
        }
        final Header responseDateHeader = backendResponse.getFirstHeader("Date");
        if (responseDateHeader == null) {
            return false;
        }
        try {
            final Date entryDate = DateUtils.parseDate(entryDateHeader.getValue());
            final Date responseDate = DateUtils.parseDate(responseDateHeader.getValue());
            return responseDate.before(entryDate);
        } catch (final DateParseException e) {
            //
        }
        return false;
    }

    public IOReactorStatus getStatus() {
        return this.backend.getStatus();
    }

    public void shutdown() throws InterruptedException {
        this.backend.shutdown();
    }

    public void start() {
        this.backend.start();
    }

}
