package be.nabu.eai.module.http.server.renderer;

import java.io.IOException;
import java.util.List;

import be.nabu.eai.developer.MainController;
import be.nabu.eai.developer.managers.base.BaseJAXBGUIManager;
import be.nabu.eai.repository.resources.RepositoryEntry;
import be.nabu.libs.property.api.Property;
import be.nabu.libs.property.api.Value;

public class RendererGUIManager extends BaseJAXBGUIManager<RendererConfiguration, RendererArtifact> {

	public RendererGUIManager() {
		super("Renderer", RendererArtifact.class, new RendererManager(), RendererConfiguration.class);
	}

	@Override
	protected List<Property<?>> getCreateProperties() {
		return null;
	}

	@Override
	protected RendererArtifact newInstance(MainController controller, RepositoryEntry entry, Value<?>... values) throws IOException {
		return new RendererArtifact(entry.getId(), entry.getContainer(), entry.getRepository());
	}
	
	@Override
	public String getCategory() {
		return "Web";
	}

}
