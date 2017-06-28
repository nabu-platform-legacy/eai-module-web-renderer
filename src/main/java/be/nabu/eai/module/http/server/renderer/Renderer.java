package be.nabu.eai.module.http.server.renderer;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import be.nabu.eai.module.web.application.WebApplication;
import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.HTTPCodes;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.core.DefaultHTTPResponse;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;
import be.nabu.utils.mime.impl.PlainMimeContentPart;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class Renderer implements EventHandler<HTTPRequest, HTTPResponse> {
	
	private List<String> agents = new ArrayList<String>();
	private String pathRegex;
	private WebApplication application;
	
	public Renderer(WebApplication application) {
		this.application = application;
	}

	@Override
	public HTTPResponse handle(HTTPRequest request) {
		if (request.getContent() != null && MimeUtils.getHeader("Nabu-Renderer", request.getContent().getHeaders()) != null) {
			return null;
		}
		System.out.println("RENDERER ENTERED");
		boolean resolve = pathRegex == null ? false : request.getTarget().matches(pathRegex);
		if (request.getContent() != null && agents.size() > 0) {
			Header header = MimeUtils.getHeader("User-Agent", request.getContent().getHeaders());
			if (header != null) {
				String value = MimeUtils.getFullHeaderValue(header);
				for (String agent : agents) {
					resolve = value.matches(agent);
					if (resolve) {
						break;
					}
				}
			}
		}
		System.out.println("Should render? " + resolve);
		if (resolve) {
			try {
				System.out.println("Creating new web client..." + BrowserVersion.getDefault());
				final WebClient webClient = new WebClient(BrowserVersion.BEST_SUPPORTED, new WebConnectionImpl(application.getDispatcher()));
				System.out.println("Setting custom web connection...");
				try {
					URI uri = HTTPUtils.getURI(request, false);
					System.out.println("PRERENDERING: " + uri);
					HtmlPage page = webClient.getPage(uri.toURL());
					String content = page.asXml();
					System.out.println("CONTENT IS: " + content);
					byte [] bytes = content.getBytes("UTF-8");
					if (bytes.length == 0) {
						return new DefaultHTTPResponse(200, HTTPCodes.getMessage(200), new PlainMimeEmptyPart(null, 
							new MimeHeader("Content-Length", "0")));
					}
					else {
						return new DefaultHTTPResponse(request, 200, HTTPCodes.getMessage(200), new PlainMimeContentPart(null, IOUtils.wrap(bytes, true),
							new MimeHeader("Content-Length", Integer.toString(bytes.length)),
							new MimeHeader("Content-Type", "text/html")));
					}
				}
				finally {
					webClient.close();
				}
			}
			catch (HTTPException e) {
				e.printStackTrace();
				throw e;
			}
			catch (Throwable e) {
				e.printStackTrace();
				throw new HTTPException(500, e);
			}
		}
		return null;
	}

	public List<String> getAgents() {
		return agents;
	}

	public String getPathRegex() {
		return pathRegex;
	}

	public void setPathRegex(String pathRegex) {
		this.pathRegex = pathRegex;
	}
	
}
