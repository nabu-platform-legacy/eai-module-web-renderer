package be.nabu.eai.module.http.server.renderer;

import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import be.nabu.eai.api.EnvironmentSpecific;
import be.nabu.eai.repository.api.CacheProviderArtifact;
import be.nabu.eai.repository.jaxb.ArtifactXMLAdapter;

@XmlRootElement(name = "renderer")
public class RendererConfiguration {

	private List<String> agents = new ArrayList<String>();
	private CacheProviderArtifact cacheProvider;

	@EnvironmentSpecific
	@XmlJavaTypeAdapter(value = ArtifactXMLAdapter.class)
	public CacheProviderArtifact getCacheProvider() {
		return cacheProvider;
	}
	public void setCacheProvider(CacheProviderArtifact cacheProvider) {
		this.cacheProvider = cacheProvider;
	}
	
	public List<String> getAgents() {
		return agents;
	}
	public void setAgents(List<String> agents) {
		this.agents = agents;
	}
	
}
