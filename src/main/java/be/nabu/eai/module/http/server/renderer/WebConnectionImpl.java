package be.nabu.eai.module.http.server.renderer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.authentication.api.Token;
import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.events.api.ResponseHandler;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.client.HTTPClient;
import be.nabu.libs.http.core.DefaultHTTPRequest;
import be.nabu.libs.http.core.ServerHeader;
import be.nabu.libs.http.server.SimpleAuthenticationHeader;
import be.nabu.libs.nio.api.ExceptionFormatter;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.ReadableContainer;
import be.nabu.utils.mime.api.ContentPart;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;
import be.nabu.utils.mime.impl.PlainMimeContentPart;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

import com.gargoylesoftware.htmlunit.WebConnection;
import com.gargoylesoftware.htmlunit.WebRequest;
import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.WebResponseData;
import com.gargoylesoftware.htmlunit.util.NameValuePair;

public class WebConnectionImpl implements WebConnection {

	private EventDispatcher dispatcher;
	private Token token;
	private Logger logger = LoggerFactory.getLogger(getClass());
	private HTTPClient client;
	private ExceptionFormatter<HTTPRequest, HTTPResponse> formatter;
	private String javascriptToInject;
	private boolean injected;
	private boolean ssr;

	public WebConnectionImpl(EventDispatcher dispatcher, Token token, HTTPClient client, ExceptionFormatter<HTTPRequest, HTTPResponse> formatter) {
		this.dispatcher = dispatcher;
		this.token = token;
		this.client = client;
		this.formatter = formatter;
	}
	
	@Override
	public void close() throws Exception {
		// do nothing
	}

	@Override
	public WebResponse getResponse(WebRequest arg0) throws IOException {
		logger.info("Requesting: " + arg0.getHttpMethod() + " " + arg0.getUrl());
		
		String body = arg0.getRequestBody();
		HTTPRequest request = new DefaultHTTPRequest(
				arg0.getHttpMethod().name(),
				arg0.getUrl().toString(),
				body == null || body.isEmpty() ? new PlainMimeEmptyPart(null) : new PlainMimeContentPart(null, IOUtils.wrap(body.getBytes("UTF-8"), true)));
		
		if (!arg0.getRequestParameters().isEmpty()) {
			throw new IOException("Unexpected request parameters: " + arg0.getRequestParameters());
		}
		
		Map<String, String> additionalHeaders = arg0.getAdditionalHeaders();
		for (String key : additionalHeaders.keySet()) {
			request.getContent().setHeader(new MimeHeader(key, additionalHeaders.get(key)));
		}
		
		Header hostHeader = MimeUtils.getHeader("Host", request.getContent().getHeaders());
		if (hostHeader == null) {
			request.getContent().setHeader(new MimeHeader("Host", arg0.getUrl().getHost()));
		}
		// if the host is not in sync with the request, update it
		// apparently htmlunit will by default always take the "local" host?
		// for example we were loading a page: http://172.20.23.1:9001/?$prerender
		// it contains a link to a javascript hosted on cdn: https://cdnjs.cloudflare.com/ajax/libs/jquery/3.1.1/jquery.js
		// for some reason the host header is: Host: 172.20.23.1:9001
		else if (arg0.getUrl().getHost() != null && !arg0.getUrl().getHost().equals(hostHeader.getValue())) {
			request.getContent().removeHeader("Host");
			request.getContent().setHeader(new MimeHeader("Host", arg0.getUrl().getHost()));
		}
		
		if (MimeUtils.getHeader("User-Agent", request.getContent().getHeaders()) == null) {
			request.getContent().setHeader(new MimeHeader("User-Agent", "Nabu-Renderer/1.0"));
		}
		
		request.getContent().setHeader(new MimeHeader("Nabu-Renderer", "false"));
		
		if (ssr) {
			request.getContent().setHeader(new MimeHeader(ServerHeader.REQUEST_TYPE.getName(), "ssr"));
		}
		
		if (token != null) {
			logger.debug("Adding credentials for {}", token);
			request.getContent().setHeader(new SimpleAuthenticationHeader(token));
		}
		
		Date date = new Date();
		HTTPResponse response = dispatcher.fire(request, this, new ResponseHandler<HTTPRequest, HTTPResponse>() {
			@Override
			public HTTPResponse handle(HTTPRequest arg0, Object arg1, boolean arg2) {
				if (arg1 instanceof HTTPResponse) {
					return (HTTPResponse) arg1;
				}
				else if (arg1 instanceof Exception) {
					return formatter.format(arg0, (Exception) arg1);
				}
				return null;
			}
		}, new ResponseHandler<HTTPRequest, HTTPRequest>() {
			@Override
			public HTTPRequest handle(HTTPRequest arg0, Object arg1, boolean arg2) {
				if (arg1 instanceof HTTPRequest) {
					return (HTTPRequest) arg1;
				}
				else if (arg1 instanceof Exception) {
					logger.error("Could not preprocess request", (Exception) arg1);
					throw new HTTPException(500, (Exception) arg1);
				}
				return null;
			}
		});
		
		logger.info("Renderer request [" + (new Date().getTime() - date.getTime()) + "ms] " + arg0.getUrl());
		
		// if there is no response whatsoever (not even a 404), it was not aimed at this server, could be a cdn import or something like that
		if (response == null) {
			try {
				response = client.execute(request, null, request.getTarget().startsWith("https://"), true);
				logger.info("Renderer external request [" + (new Date().getTime() - date.getTime()) + "ms] " + arg0.getUrl());
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		// do some post processing, for example to get the minified version
		else {
			HTTPResponse alteredResponse = dispatcher.fire(response, this, new ResponseHandler<HTTPResponse, HTTPResponse>() {
				@Override
				public HTTPResponse handle(HTTPResponse original, Object proposed, boolean isLast) {
					if (proposed instanceof Exception) {
						return formatter.format(request, (Exception) proposed);
					}
					else if (proposed instanceof HTTPResponse) {
						return (HTTPResponse) proposed;
					}
					return null;
				}
			});
			if (alteredResponse != null) {
				logger.debug("Altered response to " + request.hashCode());
				response = alteredResponse;
			}
			logger.info("Renderer altered response [" + (new Date().getTime() - date.getTime()) + "ms] " + arg0.getUrl());
		}
		
		if (response == null) {
			throw new RuntimeException("No response found");
		}
		
		byte [] content = null;
		if (response.getContent() instanceof ContentPart) {
			ReadableContainer<ByteBuffer> readable = ((ContentPart) response.getContent()).getReadable();
			if (readable != null) {
				content = IOUtils.toBytes(readable);
			}
		}
		String contentType = MimeUtils.getContentType(response.getContent().getHeaders());
		if (!injected && content != null && contentType.equals("application/javascript") && javascriptToInject != null) {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			baos.write((javascriptToInject + "\n\n").getBytes("UTF-8"));
			baos.write(content);
			content = baos.toByteArray();
			injected = true;
		}
		
		List<NameValuePair> responseHeaders = new ArrayList<NameValuePair>();
		for (Header header : response.getContent().getHeaders()) {
			if (header.getName().equalsIgnoreCase("Content-Encoding") || header.getName().equalsIgnoreCase("Transfer-Encoding")) {
				continue;
			}
			responseHeaders.add(new NameValuePair(header.getName(), MimeUtils.getFullHeaderValue(header)));
		}
		WebResponseData data = new WebResponseData(content, response.getCode(), response.getMessage(), responseHeaders);
		
		logger.debug("Response for " + arg0.getHttpMethod() + " " + arg0.getUrl() + ": " + response.getCode() + " " + response.getMessage());
		
		logger.info("Renderer done [" + (new Date().getTime() - date.getTime()) + "ms] " + arg0.getUrl());
		return new WebResponse(data, arg0, new Date().getTime() - date.getTime());
	}

	public String getJavascriptToInject() {
		return javascriptToInject;
	}

	public void setJavascriptToInject(String javascriptToInject) {
		this.javascriptToInject = javascriptToInject;
	}

	public boolean isSsr() {
		return ssr;
	}

	public void setSsr(boolean ssr) {
		this.ssr = ssr;
	}
}
