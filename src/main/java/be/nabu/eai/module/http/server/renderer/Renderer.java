package be.nabu.eai.module.http.server.renderer;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.eai.module.http.server.RepositoryExceptionFormatter;
import be.nabu.eai.module.web.application.WebApplication;
import be.nabu.libs.authentication.api.Token;
import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.HTTPCodes;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.client.HTTPClient;
import be.nabu.libs.http.core.DefaultHTTPResponse;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.api.ModifiablePart;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;
import be.nabu.utils.mime.impl.PlainMimeContentPart;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.NicelyResynchronizingAjaxController;
import com.gargoylesoftware.htmlunit.SilentCssErrorHandler;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.WebRequest;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class Renderer implements EventHandler<HTTPRequest, HTTPResponse> {
	
	private List<String> agents = new ArrayList<String>();
	private String pathRegex;
	private WebApplication application;
	private HTTPClient client;
	private static Logger logger = LoggerFactory.getLogger(Renderer.class);
	
	public Renderer(WebApplication application, HTTPClient client) {
		this.application = application;
		this.client = client;
	}

	@Override
	public HTTPResponse handle(HTTPRequest request) {
		if (request.getContent() != null && MimeUtils.getHeader("Nabu-Renderer", request.getContent().getHeaders()) != null) {
			return null;
		}
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
		if (resolve) {
			return execute(application, request, null, client);
		}
		return null;
	}

	public static HTTPResponse execute(WebApplication application, HTTPRequest request, Token token, HTTPClient client) {
		try {
			WebConnectionImpl webConnection = new WebConnectionImpl(application.getDispatcher(), token, client, new RepositoryExceptionFormatter(application.getConfig().getVirtualHost().getConfig().getServer()));
			BrowserVersion browserVersion = BrowserVersion.BEST_SUPPORTED;
			if (!browserVersion.getUserAgent().contains("Nabu-Renderer")) {
				browserVersion.setUserAgent(browserVersion.getUserAgent() + " Nabu-Renderer/1.0");
			}
			final WebClient webClient = new WebClient(browserVersion, webConnection);
			
			// "should" optimize the javascript code but doesn't...not really
//			JavaScriptEngine sriptEngine = (JavaScriptEngine) webClient.getJavaScriptEngine();
//			HtmlUnitContextFactory factory = sriptEngine.getContextFactory();
//			Context context = factory.enterContext();
//			context.setOptimizationLevel(9);
			
			webClient.setAjaxController(new NicelyResynchronizingAjaxController());
			webClient.setCssErrorHandler(new SilentCssErrorHandler());
			webClient.getOptions().setCssEnabled(true);
			webClient.getOptions().setJavaScriptEnabled(true);
			webClient.getOptions().setPopupBlockerEnabled(true);
			webClient.getOptions().setTimeout(30000);
			webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
			webClient.getOptions().setThrowExceptionOnScriptError(true);
			webClient.getOptions().setPrintContentOnFailingStatusCode(true);
			
			try {
				// we can expand later to support body posts etc if necessary
				URI uri = HTTPUtils.getURI(request, false);
				WebRequest rendererRequest = new WebRequest(uri.toURL());
				if (request.getContent() != null) {
					for (Header header : request.getContent().getHeaders()) {
						rendererRequest.setAdditionalHeader(header.getName(), MimeUtils.getFullHeaderValue(header));
					}
				}
				logger.info("Loading page: " + uri);
				HtmlPage page = webClient.getPage(rendererRequest);
//				logger.debug("Initializing page: " + uri);
//				page.initialize();
				// waiting for background javascript tasks to finish...
				logger.debug("Waiting for background tasks...");
				webClient.waitForBackgroundJavaScript(10000);
				logger.debug("Getting page as xml");
				String content = page.asXml();
				// it will generate CDATA tags inside all script tags
				// this is fine for javascript but does not work with templates
				content = content.replaceAll("//[\\s]*<!\\[CDATA\\[", "");
				content = content.replaceAll("//[\\s]*\\]\\]>", "");
				byte [] bytes = content.getBytes("UTF-8");
				logger.debug("Received: " + bytes.length + " bytes as content");
				if (bytes.length == 0) {
					return new DefaultHTTPResponse(request, 200, HTTPCodes.getMessage(200), new PlainMimeEmptyPart(null, 
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
			logger.error("HTTP Exception during rendering", e);
			throw e;
		}
		catch (Throwable e) {
			logger.error("Unknown Exception during rendering", e);
			throw new HTTPException(500, e);
		}
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
