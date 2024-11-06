/*
* Copyright (C) 2017 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package be.nabu.eai.module.http.server.renderer;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
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
import be.nabu.libs.cache.api.Cache;
import be.nabu.libs.cache.api.CacheEntry;
import be.nabu.libs.cache.api.CacheRefresher;
import be.nabu.libs.cache.api.ExplorableCache;
import be.nabu.libs.cache.impl.ByteSerializer;
import be.nabu.libs.cache.impl.StringSerializer;
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
	private Map<String, Cache> caches = new HashMap<String, Cache>();
	
	public RendererArtifact(String id, ResourceContainer<?> directory, Repository repository) {
		super(id, directory, repository, "renderer.xml", RendererConfiguration.class);
	}

	@Override
	public void start(WebApplication artifact, String path) throws IOException {
		HTTPClient client = getHttpClient();
		
		Renderer renderer = new Renderer(artifact, client, getCache(artifact));
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

	private HTTPClient getHttpClient() {
		try {
			return Services.newClient(getConfig().getHttpClient());
		}
		catch (Exception e) {
			throw new RuntimeException(e);
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
		Renderer.execute(artifact, new DefaultHTTPRequest("GET", artifact.getServerPath(), content), null, client, null, true, false);
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
		// we currently don't clear the cache on removal, we generally want persistent caches (if at all possible)
		if (caches.containsKey(key)) {
			synchronized(caches) {
				if (caches.containsKey(key)) {
					caches.remove(key);
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
	
	private Cache getCache(final WebApplication application) {
		if (!caches.containsKey(application.getId()) && getConfig().getCacheProvider() != null) {
			synchronized(caches) {
				if (!caches.containsKey(application.getId()) && getConfig().getCacheProvider() != null) {
					CacheRefresher refresher = new CacheRefresher() {
						@Override
						public Object refresh(Object key) throws IOException {
							try {
								URI uri = new URI(key.toString());
								return calculateCache(application, uri);
							}
							catch (URISyntaxException e) {
								logger.error("Could not refresh cache for: " + key, e);
							}
							return null;
						}
					};
					caches.put(application.getId(), getConfig().getCacheProvider().create(getId(), 0, 0, new StringSerializer(Charset.forName("UTF-8")), new ByteSerializer(), refresher, null));
				}
			}
		}
		return caches.get(application.getId());
	}
	
	protected boolean isCached(WebApplication application, URI url) {
		Cache cache = getCache(application);
		if (cache instanceof ExplorableCache) {
			return ((ExplorableCache) cache).getEntry(url.toString()) != null;
		}
		else if (cache != null) {
			try {
				return cache.get(url.toString()) != null;
			}
			catch (IOException e) {
				return false;
			}
		}
		return false;
	}
	
	public void cache(WebApplication application, URI url, Date lastModified) {
		Cache cache = getCache(application);
		try {
			if (cache instanceof ExplorableCache) {
				CacheEntry entry = ((ExplorableCache) cache).getEntry(url.toString());
				// if the url was modified after the cache was created, refresh it
				if (entry != null && lastModified != null && lastModified.after(entry.getLastModified())) {
					logger.info("Refreshing SSR cache for: " + url);
					cache.refresh(url.toString());
				}
				// not cache yet, do so
				else if (entry == null) {
					logger.info("Calculating SSR cache for: " + url);
					cache.put(url.toString(), calculateCache(application, url));
				}
				else {
					logger.debug("SSR cache still valid for: " + url);
				}
			}
			else if (cache != null) {
				Object object = cache.get(url.toString());
				if (object == null) {
					cache.put(url.toString(), calculateCache(application, url));	
				}
				else {
					logger.debug("SSR cache still valid for: " + url);
				}
			}
		}
		catch (Exception e) {
			logger.warn("Could not cache: " + url, e);
		}
	}
	
	private byte [] calculateCache(WebApplication application, URI uri) {
		HTTPRequest request = new DefaultHTTPRequest("GET", uri.toString(), new PlainMimeEmptyPart(null, 
			new MimeHeader("Host", uri.getHost()),
			new MimeHeader("Content-Length", "0")
		));
		return Renderer.executeAsBytes(application, request, null, getHttpClient(), null, false, false);
	}
}
