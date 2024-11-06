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
