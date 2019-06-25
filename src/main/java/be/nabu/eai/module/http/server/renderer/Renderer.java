package be.nabu.eai.module.http.server.renderer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.eai.module.http.server.RepositoryExceptionFormatter;
import be.nabu.eai.module.web.application.WebApplication;
import be.nabu.eai.repository.EAIRepositoryUtils;
import be.nabu.eai.repository.Notification;
import be.nabu.libs.authentication.api.Token;
import be.nabu.libs.cache.api.Cache;
import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.HTTPCodes;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.client.HTTPClient;
import be.nabu.libs.http.core.DefaultHTTPResponse;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.validator.api.ValidationMessage.Severity;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;
import be.nabu.utils.mime.impl.PlainMimeContentPart;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.NicelyResynchronizingAjaxController;
import com.gargoylesoftware.htmlunit.SilentCssErrorHandler;
import com.gargoylesoftware.htmlunit.TextPage;
import com.gargoylesoftware.htmlunit.UnexpectedPage;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.WebRequest;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.xml.XmlPage;

public class Renderer implements EventHandler<HTTPRequest, HTTPResponse> {
	
	// https://www.keycdn.com/blog/web-crawlers
	private static List<String> defaultAgents = Arrays.asList(new String [] {
		// ------------------------- GOOGLE -----------------------------
		// use https://www.google.com/webmasters/tools/googlebot-fetch?pli=1 to debug
		// google (multiple search bots)
		"(?i).*Googlebot.*",
		// google ads
		"(?i).*AdsBot.*",
		"(?i).*Mediapartners-Google.*",
		"(?i).*developers.google.com.*",
		
		// -------------------------- MICROSOFT ------------------------
		// https://www.bing.com/toolbox/webmaster/
		"(?i).*Bingbot.*",
		
		// -------------------------- LINKEDIN -------------------------
		"(?i).*LinkedInBot.*",
		
		// -------------------------- TWITTER -------------------------
		"(?i).*TwitterBot.*",
		
		// -------------------------- PINTEREST -------------------------
		"(?i).*Pinterestbot.*",
		
		// -------------------------- YAHOO ----------------------------
		"(?i).*Slurp.*",
		
		// -------------------------- OTHER ----------------------------
		"(?i).*DuckDuckBot.*",
		"(?i).*Baiduspider.*",
		"(?i).*YandexBot.*",
		"(?i).*Sogou.*",
		"(?i).*Exabot.*",
		"(?i).*facebook.com.*",
		"(?i).*crawler@alexa.com.*",
		
		// catch all
		"(?i).*(bot|googlebot|crawler|spider|robot|crawling).*"
	});
	
	private List<String> agents = new ArrayList<String>();
	private String pathRegex;
	private WebApplication application;
	private HTTPClient client;

	private boolean secure;
	private static Logger logger = LoggerFactory.getLogger(Renderer.class);

	private Cache cache;

	public Renderer(WebApplication application, HTTPClient client, Cache cache) {
		this.application = application;
		this.client = client;
		this.cache = cache;
		
		// in the future we may want to toggle this behavior
		this.agents.addAll(defaultAgents);
		
		this.secure = application.getConfig().getVirtualHost() != null && application.getConfig().getVirtualHost().getConfig().getServer() != null 
			? application.getConfig().getVirtualHost().getConfig().getServer().isSecure()
			: false;
	}

	@Override
	public HTTPResponse handle(HTTPRequest request) {
		if (request.getContent() != null && MimeUtils.getHeader("Nabu-Renderer", request.getContent().getHeaders()) != null) {
			return null;
		}
		String userAgent = null;
		boolean resolve = pathRegex == null ? false : request.getTarget().matches(pathRegex);
		if (request.getContent() != null && agents.size() > 0) {
			Header header = MimeUtils.getHeader("User-Agent", request.getContent().getHeaders());
			if (header != null) {
				userAgent = MimeUtils.getFullHeaderValue(header);
				for (String agent : agents) {
					boolean agentMatch = userAgent.matches(agent);
					if (agentMatch) {
						resolve = true;
						break;
					}
				}
			}
		}
		if (resolve) {
			URI uri;
			try {
				uri = HTTPUtils.getURI(request, secure);
			}
			catch (Exception e) {
				throw new HTTPException(500, e);
			}

			Notification notification = new Notification();
			notification.setContext(Arrays.asList(application.getId()));
			notification.setCode("RENDERER-0");
			notification.setType("nabu.web.renderer.bot");
			
			byte [] bytes = null;
			if (cache != null) {
				try {
					// check if we have a cached version
					bytes = (byte[]) cache.get(uri.toString());
				}
				catch (Exception e) {
					throw new HTTPException(500, e);
				}
			}
			
			if (bytes == null) {
				Date date = new Date();
				// we do _not_ want to bypass stuff like the password protector if doing SSR on external request
				// we also don't need css
				bytes = executeAsBytes(application, request, null, client, null, false, false);
				if (cache != null) {
					try {
						cache.put(uri.toString(), bytes);
					}
					catch (IOException e) {
						throw new HTTPException(500, e);
					}
				}
				notification.setMessage("Cache miss for: " + uri + " -- by: " + userAgent);
				notification.setDescription("Rendering took: " + (new Date().getTime() - date.getTime()) + "ms");
				notification.setSeverity(Severity.WARNING);
			}
			else {
				notification.setMessage("Cache hit for: " + uri + " -- by: " + userAgent);
				notification.setSeverity(Severity.INFO);
			}
			
			EAIRepositoryUtils.fireAsync(application.getRepository(), notification, this);
			
			// now that we have the bytes as separate, store them
			return wrapIntoResponse(request, bytes);
		}
		return null;
	}

	public static HTTPResponse execute(WebApplication application, HTTPRequest request, Token token, HTTPClient client, String javascriptToInject, boolean setSsr, Boolean css) {
		byte [] bytes = executeAsBytes(application, request, token, client, javascriptToInject, setSsr, css);
		return wrapIntoResponse(request, bytes);
	}
	
	static HTTPResponse wrapIntoResponse(HTTPRequest request, byte [] bytes) {
		if (bytes.length == 0) {
			return new DefaultHTTPResponse(request, 204, HTTPCodes.getMessage(200), new PlainMimeEmptyPart(null, 
				new MimeHeader("Content-Length", "0")));
		}
		else {
			return new DefaultHTTPResponse(request, 200, HTTPCodes.getMessage(200), new PlainMimeContentPart(null, IOUtils.wrap(bytes, true),
				new MimeHeader("Content-Length", Integer.toString(bytes.length)),
				new MimeHeader("Content-Type", "text/html")));
		}
	}
	
	static byte [] executeAsBytes(WebApplication application, HTTPRequest request, Token token, HTTPClient client, String javascriptToInject, boolean setSsr, Boolean css) {
		try {
			WebConnectionImpl webConnection = new WebConnectionImpl(application.getDispatcher(), token, client, new RepositoryExceptionFormatter(application.getConfig().getVirtualHost().getConfig().getServer()));
			webConnection.setJavascriptToInject(javascriptToInject);
			webConnection.setSsr(setSsr);
			BrowserVersion browserVersion = BrowserVersion.BEST_SUPPORTED;
			if (!browserVersion.getUserAgent().contains("Nabu-Renderer")) {
				browserVersion.setUserAgent(browserVersion.getUserAgent() + " Nabu-Renderer/1.0");
			}
			// the initial request will get the headers we want, but any requests that follow from that (e.g. to fetch javascript, css...) will use "default" header stuffs
			List<String> acceptedLanguages = MimeUtils.getAcceptedLanguages(request.getContent().getHeaders());
			if (acceptedLanguages.size() > 0) {
				browserVersion.setBrowserLanguage(acceptedLanguages.get(0));
			}
			final WebClient webClient = new WebClient(browserVersion, webConnection);
			
			// "should" optimize the javascript code but doesn't...not really
//			JavaScriptEngine sriptEngine = (JavaScriptEngine) webClient.getJavaScriptEngine();
//			HtmlUnitContextFactory factory = sriptEngine.getContextFactory();
//			Context context = factory.enterContext();
//			context.setOptimizationLevel(9);
			webClient.setAjaxController(new NicelyResynchronizingAjaxController());
			webClient.setCssErrorHandler(new SilentCssErrorHandler());
			webClient.getOptions().setCssEnabled(css == null || css);
			webClient.getOptions().setJavaScriptEnabled(true);
			webClient.getOptions().setPopupBlockerEnabled(true);
			webClient.getOptions().setTimeout(30000);
			webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
			webClient.getOptions().setThrowExceptionOnScriptError(true);
			webClient.getOptions().setPrintContentOnFailingStatusCode(true);
			webClient.getOptions().setDownloadImages(false);
			
			try {
				// we can expand later to support body posts etc if necessary
				boolean secure = application.getConfig().getVirtualHost() != null && application.getConfig().getVirtualHost().getConfig().getServer() != null && application.getConfig().getVirtualHost().getConfig().getServer().isSecure();
				URI uri = HTTPUtils.getURI(request, secure);
				WebRequest rendererRequest = new WebRequest(uri.toURL());
				if (request.getContent() != null) {
					for (Header header : request.getContent().getHeaders()) {
						rendererRequest.setAdditionalHeader(header.getName(), MimeUtils.getFullHeaderValue(header));
					}
				}
				logger.info("Loading page: " + uri);
				Object page = webClient.getPage(rendererRequest);
//				logger.debug("Initializing page: " + uri);
//				page.initialize();
				// waiting for background javascript tasks to finish...
				logger.debug("Waiting for background tasks...");
				webClient.waitForBackgroundJavaScript(10000);
				logger.debug("Getting page as xml");
				byte [] bytes = null;
				String content = null;
				if (page instanceof HtmlPage) {
					content = ((HtmlPage) page).asXml();
				}
				else if (page instanceof TextPage) {
					content = ((TextPage) page).getContent();
				}
				else if (page instanceof XmlPage) {
					content = ((XmlPage) page).asXml();
				}
				else if (page instanceof UnexpectedPage) {
					InputStream stream = ((UnexpectedPage) page).getInputStream();
					try {
						bytes = IOUtils.toBytes(IOUtils.wrap(stream));
					}
					finally {
						stream.close();
					}
				}
				else {
					throw new HTTPException(502, "Unknown page type: " + page);
				}
				
				if (bytes == null && content != null) {
					// it will generate CDATA tags inside all script tags
					// this is fine for javascript but does not work with templates
					content = content.replaceAll("//[\\s]*<!\\[CDATA\\[", "");
					content = content.replaceAll("//[\\s]*\\]\\]>", "");
					bytes = content.getBytes("UTF-8");
				}
				logger.debug("Received: " + bytes.length + " bytes as content");
				return bytes;
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
