package be.nabu.eai.module.http.server.renderer;

import be.nabu.eai.repository.api.Repository;
import be.nabu.eai.repository.managers.base.JAXBArtifactManager;
import be.nabu.libs.resources.api.ResourceContainer;

public class RendererManager extends JAXBArtifactManager<RendererConfiguration, RendererArtifact> {

	public RendererManager() {
		super(RendererArtifact.class);
	}

	@Override
	protected RendererArtifact newInstance(String id, ResourceContainer<?> container, Repository repository) {
		return new RendererArtifact(id, container, repository);
	}

}
