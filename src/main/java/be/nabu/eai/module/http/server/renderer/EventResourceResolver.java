package be.nabu.eai.module.http.server.renderer;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;

import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.resources.URIUtils;
import be.nabu.libs.resources.api.Resource;
import be.nabu.libs.resources.api.ResourceResolver;

public class EventResourceResolver implements ResourceResolver {

	private DispatcherResolver resolver;

	public EventResourceResolver(DispatcherResolver resolver) {
		this.resolver = resolver;
	}
	
	@Override
	public Resource getResource(URI uri, Principal principal) throws IOException {
		try {
			URI child = new URI(URIUtils.encodeURI(uri.getSchemeSpecificPart()));
			EventDispatcher dispatcher = resolver.getDispatcher(child.getScheme());
			if (dispatcher == null) {
				throw new IOException("Could not find the dispatcher: " + child.getScheme());
			}
			return new EventResource(dispatcher, child, null);
		}
		catch (URISyntaxException e) {
			throw new IOException(e);
		}
	}
	
	@Override
	public List<String> getDefaultSchemes() {
		return Arrays.asList(new String [] { "http-event" });
	}

}
