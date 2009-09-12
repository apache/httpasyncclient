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
package org.apache.http.impl.nio.conn;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;

import org.apache.commons.logging.Log;
import org.apache.http.Header;
import org.apache.http.HttpException;
import org.apache.http.HttpMessage;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseFactory;
import org.apache.http.impl.nio.DefaultNHttpClientConnection;
import org.apache.http.nio.NHttpMessageParser;
import org.apache.http.nio.NHttpMessageWriter;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.reactor.SessionInputBuffer;
import org.apache.http.nio.reactor.SessionOutputBuffer;
import org.apache.http.nio.util.ByteBufferAllocator;
import org.apache.http.params.HttpParams;

public class LoggingNHttpClientConnection extends DefaultNHttpClientConnection {

    private final Log headerlog;

    public LoggingNHttpClientConnection(
    		final Log headerlog,
            final IOSession iosession,
            final HttpResponseFactory responseFactory,
            final ByteBufferAllocator allocator,
            final HttpParams params) {
        super(iosession, responseFactory, allocator, params);
        this.headerlog = headerlog;
	}

    @Override
	protected NHttpMessageWriter createRequestWriter(
			final SessionOutputBuffer buffer, 
			final HttpParams params) {
		return new LoggingNHttpMessageWriter(
		        super.createRequestWriter(buffer, params));
	}

	@Override
	protected NHttpMessageParser createResponseParser(
			final SessionInputBuffer buffer, 
			final HttpResponseFactory responseFactory,
			final HttpParams params) {
		return new LoggingNHttpMessageParser(
		        super.createResponseParser(buffer, responseFactory, params));
	}

	class LoggingNHttpMessageWriter implements NHttpMessageWriter {

		private final NHttpMessageWriter writer;
		
		public LoggingNHttpMessageWriter(final NHttpMessageWriter writer) {
			super();
			this.writer = writer;
		}
		
		public void reset() {
			this.writer.reset();
		}

		public void write(final HttpMessage message) throws IOException, HttpException {
	        if (message != null && headerlog.isDebugEnabled()) {
	        	HttpRequest request = (HttpRequest) message; 
	            headerlog.debug(">> " + request.getRequestLine().toString());
	            Header[] headers = request.getAllHeaders();
	            for (int i = 0; i < headers.length; i++) {
	                headerlog.debug(">> " + headers[i].toString());
	            }
	        }
	        this.writer.write(message);
		}
		
	}
	
	class LoggingNHttpMessageParser implements NHttpMessageParser {

		private final NHttpMessageParser parser;
		
		public LoggingNHttpMessageParser(final NHttpMessageParser parser) {
			super();
			this.parser = parser;
		}
		
		public void reset() {
			this.parser.reset();
		}

		public int fillBuffer(final ReadableByteChannel channel) throws IOException {
			return this.parser.fillBuffer(channel);
		}

		public HttpMessage parse() throws IOException, HttpException {
			HttpMessage message = this.parser.parse();
			if (headerlog.isDebugEnabled()) {
				HttpResponse response = (HttpResponse) message; 
		        if (response != null && headerlog.isDebugEnabled()) {
		            headerlog.debug("<< " + response.getStatusLine().toString());
		            Header[] headers = response.getAllHeaders();
		            for (int i = 0; i < headers.length; i++) {
		                headerlog.debug("<< " + headers[i].toString());
		            }
		        }
			}
			return message;
		}
		
	}

}
