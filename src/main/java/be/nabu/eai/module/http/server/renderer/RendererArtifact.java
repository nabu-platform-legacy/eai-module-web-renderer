package be.nabu.eai.module.http.server.renderer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nabu.protocols.http.client.Services;
import be.nabu.eai.module.web.application.WebApplication;
import be.nabu.eai.module.web.application.WebFragment;
import be.nabu.eai.module.web.application.WebFragmentPriority;
import be.nabu.eai.repository.EAIResourceRepository;
import be.nabu.eai.repository.api.LanguageProvider;
import be.nabu.eai.repository.api.Repository;
import be.nabu.eai.repository.artifacts.jaxb.JAXBArtifact;
import be.nabu.libs.authentication.api.Permission;
import be.nabu.libs.events.api.EventSubscription;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.client.HTTPClient;
import be.nabu.libs.http.core.DefaultHTTPRequest;
import be.nabu.libs.resources.api.ResourceContainer;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

public class RendererArtifact extends JAXBArtifact<RendererConfiguration> implements WebFragment {

	private Logger logger = LoggerFactory.getLogger(getClass());
	private Map<String, EventSubscription<?, ?>> subscriptions = new HashMap<String, EventSubscription<?, ?>>();
	
	public RendererArtifact(String id, ResourceContainer<?> directory, Repository repository) {
		super(id, directory, repository, "renderer.xml", RendererConfiguration.class);
	}

	@Override
	public void start(WebApplication artifact, String path) throws IOException {
		HTTPClient client;
		try {
			client = Services.newClient(getConfig().getHttpClient());
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
		Renderer renderer = new Renderer(artifact, client);
		if (EAIResourceRepository.isDevelopment()) {
			renderer.setPathRegex(".*\\?.*\\$prerender");
		}
		if (getConfig().getAgents() != null) {
			renderer.getAgents().addAll(getConfig().getAgents());
		}
		EventSubscription<HTTPRequest, HTTPResponse> subscription = artifact.getDispatcher().subscribe(HTTPRequest.class, renderer);
		subscription.promote();
		subscriptions.put(getKey(artifact, path), subscription);
		
		// no sense in warming up a development server
		if (!EAIResourceRepository.isDevelopment() && getConfig().isWarmup() && artifact.getConfig().getVirtualHost() != null) {
			logger.info("Warming up application " + artifact.getId() + "...");
			Date date = new Date();
			LanguageProvider languageProvider = artifact.getLanguageProvider();
			if (languageProvider != null && languageProvider.getSupportedLanguages() != null) {
				for (String language : languageProvider.getSupportedLanguages()) {
					logger.info("Warming up language: " + language);
					load(artifact, client, language);
				}
			}
			else {
				logger.info("No language provider found, warming up without language");
				load(artifact, client, null);
			}
			logger.info("Application " + artifact.getId() + " warmed up in " + ((new Date().getTime() - date.getTime()) / 1000.0) + "s");
		}
	}

	private void load(WebApplication artifact, HTTPClient client, String language) throws IOException {
		String host = artifact.getConfig().getVirtualHost().getConfig().getHost();
		PlainMimeEmptyPart content = new PlainMimeEmptyPart(null, 
			new MimeHeader("Content-Length", "0"),
			new MimeHeader("Host", host == null ? "localhost" : host));
		if (language != null) {
			content.setHeader(new MimeHeader("Cookie", "language=" + language));
		}
		Renderer.execute(artifact, new DefaultHTTPRequest("GET", artifact.getServerPath(), content), null, client, null, true);
	}

	@Override
	public void stop(WebApplication artifact, String path) {
		String key = getKey(artifact, path);
		if (subscriptions.containsKey(key)) {
			synchronized(subscriptions) {
				if (subscriptions.containsKey(key)) {
					subscriptions.get(key).unsubscribe();
					subscriptions.remove(key);
				}
			}
		}
	}

	@Override
	public List<Permission> getPermissions(WebApplication artifact, String path) {
		return new ArrayList<Permission>();
	}

	@Override
	public boolean isStarted(WebApplication artifact, String path) {
		return subscriptions.containsKey(getKey(artifact, path));
	}

	private String getKey(WebApplication artifact, String path) {
		return artifact.getId() + ":" + path;
	}
	
	public WebFragmentPriority getPriority() {
		return WebFragmentPriority.LOWEST;
	}
}
