package be.nabu.eai.module.http.server.renderer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.events.api.ResponseHandler;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.core.DefaultHTTPRequest;
import be.nabu.utils.io.IOUtils;
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

	public WebConnectionImpl(EventDispatcher dispatcher) {
		this.dispatcher = dispatcher;
	}
	
	@Override
	public void close() throws Exception {
		// do nothing
	}

	@Override
	public WebResponse getResponse(WebRequest arg0) throws IOException {
		String body = arg0.getRequestBody();
		HTTPRequest request = new DefaultHTTPRequest(
				arg0.getHttpMethod().name(),
				arg0.getUrl().toString(),
				body == null || body.isEmpty() ? new PlainMimeEmptyPart(null) : new PlainMimeContentPart(null, IOUtils.wrap(body.getBytes("UTF-8"), true)));
		
		if (!arg0.getRequestParameters().isEmpty()) {
			throw new IOException("Unexpected request parameters: " + arg0.getRequestParameters());
		}
		
		Map<String, String> additionalHeaders = arg0.getAdditionalHeaders();
		System.out.println("HEADERS ARE: " + additionalHeaders);
		for (String key : additionalHeaders.keySet()) {
			request.getContent().setHeader(new MimeHeader(key, additionalHeaders.get(key)));
		}
		
		if (MimeUtils.getHeader("Host", request.getContent().getHeaders()) == null) {
			System.out.println("Setting host...");
			request.getContent().setHeader(new MimeHeader("Host", arg0.getUrl().getAuthority()));
		}
		
		request.getContent().setHeader(new MimeHeader("Nabu-Renderer", "false"));
		
		Date date = new Date();
		HTTPResponse response = dispatcher.fire(request, this, new ResponseHandler<HTTPRequest, HTTPResponse>() {
			@Override
			public HTTPResponse handle(HTTPRequest arg0, Object arg1, boolean arg2) {
				if (arg1 instanceof HTTPResponse) {
					return (HTTPResponse) arg1;
				}
				else if (arg1 instanceof Exception) {
					System.out.println("EXCEPTION: " + arg1);
					((Exception) arg1).printStackTrace();
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
					System.out.println("EXCEPTION: " + arg1);
					((Exception) arg1).printStackTrace();
				}
				return null;
			}
		});
		
		byte [] content = null;
		if (response.getContent() instanceof ContentPart) {
			content = IOUtils.toBytes(((ContentPart) response.getContent()).getReadable());
		}
		
		List<NameValuePair> responseHeaders = new ArrayList<NameValuePair>();
		for (Header header : response.getContent().getHeaders()) {
			responseHeaders.add(new NameValuePair(header.getName(), MimeUtils.getFullHeaderValue(header)));
		}
		WebResponseData data = new WebResponseData(content, response.getCode(), response.getMessage(), responseHeaders);
		
		return new WebResponse(data, arg0, new Date().getTime() - date.getTime());
	}

}
