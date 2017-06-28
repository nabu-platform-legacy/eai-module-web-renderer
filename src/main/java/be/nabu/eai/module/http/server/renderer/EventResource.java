package be.nabu.eai.module.http.server.renderer;

import java.io.IOException;
import java.net.URI;

import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.events.api.ResponseHandler;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.core.DefaultHTTPRequest;
import be.nabu.libs.resources.URIUtils;
import be.nabu.libs.resources.api.LocatableResource;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.resources.api.ResourceContainer;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.ReadableContainer;
import be.nabu.utils.mime.api.ContentPart;
import be.nabu.utils.mime.api.ModifiablePart;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

public class EventResource implements LocatableResource, ReadableResource {

	private String contentType;
	private URI uri;
	private EventDispatcher dispatcher;

	public EventResource(EventResource original, String path, String contentType) {
		this(original.dispatcher, URIUtils.getChild(original.getUri(), path), contentType);
	}
	
	public EventResource(EventDispatcher dispatcher, URI uri, String contentType) {
		this.dispatcher = dispatcher;
		this.uri = uri;
		this.contentType = contentType;
		if (this.contentType == null) {
			if (uri.getPath().contains("css")) {
				this.contentType = "text/css";
			}
			else if (uri.getPath().endsWith("js") || uri.getPath().contains("javascript")) {
				this.contentType = "application/javascript";
			}
			else {
				this.contentType = "text/html";
			}
		}
	}
	
	@Override
	public String getContentType() {
		return contentType;
	}

	@Override
	public String getName() {
		return URIUtils.getName(uri);
	}

	@Override
	public ResourceContainer<?> getParent() {
		return null;
	}

	@Override
	public URI getUri() {
		return uri;
	}

	@Override
	public ReadableContainer<ByteBuffer> getReadable() throws IOException {
		HTTPResponse response = dispatcher.fire(new DefaultHTTPRequest("GET", uri.getPath(), new PlainMimeEmptyPart(null, 
				new MimeHeader("Content-Length", "0"),
				new MimeHeader("Host", uri.getAuthority() == null ? "localhost" : uri.getAuthority()))), this, new ResponseHandler<HTTPRequest, HTTPResponse>() {
			@Override
			public HTTPResponse handle(HTTPRequest event, Object response, boolean isLast) {
				if (response instanceof HTTPResponse) {
					return (HTTPResponse) response;
				}
				return null;
			}
		});
		if (response == null) {
			throw new IOException("No response provided");
		}
		else if (response.getCode() < 200 || response.getCode() >= 300) {
			throw new IOException("The response was not successful: " + response.getCode() + " / " + response.getMessage());
		}
		ModifiablePart content = response.getContent();
		if (content != null) {
			String contentType = MimeUtils.getContentType(content.getHeaders());
			if (contentType != null) {
				this.contentType = contentType;
			}
		}
		if (content instanceof ContentPart) {
			return ((ContentPart) content).getReadable();
		}
		else {
			throw new IOException("No response content found");
		}
	}

}
