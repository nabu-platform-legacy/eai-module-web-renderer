package be.nabu.eai.module.http.server.renderer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import be.nabu.eai.module.web.application.WebApplication;
import be.nabu.eai.module.web.application.WebFragment;
import be.nabu.eai.repository.EAIResourceRepository;
import be.nabu.eai.repository.api.Repository;
import be.nabu.eai.repository.artifacts.jaxb.JAXBArtifact;
import be.nabu.libs.authentication.api.Permission;
import be.nabu.libs.events.api.EventSubscription;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.resources.api.ResourceContainer;

public class RendererArtifact extends JAXBArtifact<RendererConfiguration> implements WebFragment {

	private Map<String, EventSubscription<?, ?>> subscriptions = new HashMap<String, EventSubscription<?, ?>>();
	
	public RendererArtifact(String id, ResourceContainer<?> directory, Repository repository) {
		super(id, directory, repository, "renderer.xml", RendererConfiguration.class);
	}

	@Override
	public void start(WebApplication artifact, String path) throws IOException {
		Renderer renderer = new Renderer(artifact);
		if (EAIResourceRepository.isDevelopment()) {
			renderer.setPathRegex(".*\\?.*\\$prerender");
		}
		EventSubscription<HTTPRequest, HTTPResponse> subscription = artifact.getDispatcher().subscribe(HTTPRequest.class, renderer);
		subscription.promote();
		subscriptions.put(getKey(artifact, path), subscription);
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
	
}
